package com.skateboard.notification.adapter.in.scheduler;

import com.skateboard.notification.application.service.RetryPendingEmailDeliveriesService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the email retry pass. Mirrors {@link PendingDeliveryRetryJob}: no
 * logic here, and no scheduler lock since the work is claimed row by row
 * with {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
@ConditionalOnProperty(prefix = "email.retry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingEmailRetryJob {

    private final RetryPendingEmailDeliveriesService retryPendingEmailDeliveriesService;

    public PendingEmailRetryJob(RetryPendingEmailDeliveriesService retryPendingEmailDeliveriesService) {
        this.retryPendingEmailDeliveriesService = retryPendingEmailDeliveriesService;
    }

    @Scheduled(cron = "${email.retry.cron}")
    public void run() {
        retryPendingEmailDeliveriesService.run();
    }
}
