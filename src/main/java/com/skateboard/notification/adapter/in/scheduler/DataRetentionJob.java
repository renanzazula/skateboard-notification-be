package com.skateboard.notification.adapter.in.scheduler;

import com.skateboard.notification.application.service.PurgeExpiredDataService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers the retention pass — no logic here, matching
 * {@link PendingDeliveryRetryJob}.
 *
 * <p>No scheduler lock: the deletes are idempotent and bounded, so a second
 * instance running the same pass wastes a little work but cannot do harm. Runs
 * once a night by default.
 */
@Component
@ConditionalOnProperty(prefix = "retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataRetentionJob {

    private final PurgeExpiredDataService purgeExpiredDataService;

    public DataRetentionJob(PurgeExpiredDataService purgeExpiredDataService) {
        this.purgeExpiredDataService = purgeExpiredDataService;
    }

    @Scheduled(cron = "${retention.cron}")
    public void run() {
        purgeExpiredDataService.run();
    }
}
