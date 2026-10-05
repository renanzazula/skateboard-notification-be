package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.EmailDeliveryRepositoryPort;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.EmailDeliveryStatus;
import com.skateboard.notification.infrastructure.email.EmailRetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends emails that are still owed one — the email counterpart of
 * {@link RetryPendingDeliveriesService}, simpler because there is no device
 * to re-check and no per-type preference that applies to a confirmation or
 * an admin alert.
 */
@Service
public class RetryPendingEmailDeliveriesService {

    private static final Logger log = LoggerFactory.getLogger(RetryPendingEmailDeliveriesService.class);

    private final EmailDeliveryRepositoryPort emailDeliveryRepositoryPort;
    private final DispatchEmailService dispatchEmailService;
    private final EmailRetryProperties properties;

    public RetryPendingEmailDeliveriesService(EmailDeliveryRepositoryPort emailDeliveryRepositoryPort,
                                              DispatchEmailService dispatchEmailService,
                                              EmailRetryProperties properties) {
        this.emailDeliveryRepositoryPort = emailDeliveryRepositoryPort;
        this.dispatchEmailService = dispatchEmailService;
        this.properties = properties;
    }

    /** @return how many emails were re-sent */
    public int run() {
        Instant notAttemptedSince = Instant.now().minus(Duration.ofSeconds(properties.backoffSeconds()));
        List<EmailDelivery> claimed = emailDeliveryRepositoryPort
                .claimRetryable(properties.maxAttempts(), notAttemptedSince, properties.batchLimit());

        if (claimed.isEmpty()) {
            return 0;
        }

        DispatchEmailService.Result result = dispatchEmailService.send(claimed);
        retireExhausted(claimed);

        log.info("Retried pending emails: {} claimed, {} re-sent", claimed.size(), result.sent());
        return result.sent();
    }

    private void retireExhausted(List<EmailDelivery> deliveries) {
        deliveries.stream()
                .filter(delivery -> !delivery.hasAttemptsLeft(properties.maxAttempts()))
                .forEach(delivery -> {
                    if (delivery.getStatus() == EmailDeliveryStatus.PENDING) {
                        delivery.markFailed("Giving up after " + delivery.getAttemptCount() + " attempts");
                        emailDeliveryRepositoryPort.save(delivery);
                    }
                });
    }
}
