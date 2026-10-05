package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.EmailDeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.EmailMessage;
import com.skateboard.notification.application.port.out.EmailProviderPort;
import com.skateboard.notification.application.port.out.EmailResult;
import com.skateboard.notification.domain.model.EmailDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sends already-persisted {@link EmailDelivery} rows and records what the
 * provider said about each — the email counterpart of
 * {@link DispatchNotificationService}. Deliberately not transactional, for
 * the same reason: it makes a network call, and the rows it updates were
 * already committed before it ran, so a crash here loses no record of what
 * is still owed.
 */
@Service
public class DispatchEmailService {

    private static final Logger log = LoggerFactory.getLogger(DispatchEmailService.class);

    public record Result(int targeted, int sent, int retryable, int failed) {

        static Result none() {
            return new Result(0, 0, 0, 0);
        }
    }

    private final EmailDeliveryRepositoryPort emailDeliveryRepositoryPort;
    private final EmailProviderPort emailProviderPort;

    public DispatchEmailService(EmailDeliveryRepositoryPort emailDeliveryRepositoryPort,
                                EmailProviderPort emailProviderPort) {
        this.emailDeliveryRepositoryPort = emailDeliveryRepositoryPort;
        this.emailProviderPort = emailProviderPort;
    }

    public Result send(List<EmailDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return Result.none();
        }

        List<EmailMessage> messages = deliveries.stream()
                .map(d -> new EmailMessage(d.getRecipientEmail(), d.getSubject(), d.getBody()))
                .toList();

        deliveries.forEach(EmailDelivery::beginAttempt);

        List<EmailResult> results;
        try {
            results = emailProviderPort.send(messages);
        } catch (RuntimeException e) {
            // Mirrors DispatchNotificationService: every delivery stays
            // PENDING with its attempt counted, so the retry pass owns them.
            log.error("Email provider threw; leaving {} deliveries pending", deliveries.size(), e);
            results = List.of();
        }

        return applyResults(deliveries, results);
    }

    private Result applyResults(List<EmailDelivery> deliveries, List<EmailResult> results) {
        int sent = 0;
        int retryable = 0;
        int failed = 0;

        for (int i = 0; i < deliveries.size(); i++) {
            EmailDelivery delivery = deliveries.get(i);
            EmailResult result = i < results.size()
                    ? results.get(i)
                    : EmailResult.retryable("Provider returned no result for this message");

            switch (result.outcome()) {
                case ACCEPTED -> {
                    delivery.markSent(result.providerMessageId());
                    sent++;
                }
                case RETRYABLE -> {
                    delivery.markRetryable(result.detail());
                    retryable++;
                }
                case REJECTED -> {
                    delivery.markFailed(result.detail());
                    failed++;
                }
            }
            emailDeliveryRepositoryPort.save(delivery);
        }

        log.info("Email dispatch: {} targeted, {} sent, {} retryable, {} failed",
                deliveries.size(), sent, retryable, failed);

        return new Result(deliveries.size(), sent, retryable, failed);
    }
}
