package com.skateboard.notification.adapter.in.scheduler;

import com.skateboard.notification.application.service.RetryPendingDeliveriesService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the retry pass — no logic here, matching {@code YoutubeSyncJob} in
 * skateboard-podcast-be.
 *
 * <p>No scheduler lock: unlike that job, this one is safe to run concurrently
 * because the work is claimed row by row with {@code FOR UPDATE SKIP LOCKED},
 * so two instances split the backlog instead of duplicating it.
 */
@Component
@ConditionalOnProperty(prefix = "push.retry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingDeliveryRetryJob {

    private final RetryPendingDeliveriesService retryPendingDeliveriesService;

    public PendingDeliveryRetryJob(RetryPendingDeliveriesService retryPendingDeliveriesService) {
        this.retryPendingDeliveriesService = retryPendingDeliveriesService;
    }

    @Scheduled(cron = "${push.retry.cron}")
    public void run() {
        retryPendingDeliveriesService.run();
    }
}
