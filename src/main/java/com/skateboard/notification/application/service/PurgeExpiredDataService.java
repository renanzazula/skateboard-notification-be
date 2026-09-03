package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.RetentionRepositoryPort;
import com.skateboard.notification.infrastructure.retention.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes rows the service no longer needs (spec §40).
 *
 * <p>Without it, {@code notification}, {@code user_notification},
 * {@code notification_delivery} and {@code processed_event} grow forever; the
 * only other scheduled job here is the retry pass. The idempotency ledger in
 * particular is pure overhead within days of an event being handled.
 *
 * <p>Not transactional: each batch is a separate transaction inside
 * {@link RetentionRepositoryPort}, so progress survives a failure mid-pass and
 * no delete lock is held over a large range. A pass is bounded by
 * {@code maxBatchesPerRun} so a large first backlog drains over several runs.
 */
@Service
public class PurgeExpiredDataService {

    private static final Logger log = LoggerFactory.getLogger(PurgeExpiredDataService.class);

    private final RetentionRepositoryPort retentionRepositoryPort;
    private final RetentionProperties properties;

    public PurgeExpiredDataService(RetentionRepositoryPort retentionRepositoryPort,
                                   RetentionProperties properties) {
        this.retentionRepositoryPort = retentionRepositoryPort;
        this.properties = properties;
    }

    public record PurgeReport(int notificationsDeleted, int processedEventsDeleted) {
    }

    public PurgeReport run() {
        Instant now = Instant.now();

        int notifications = purge("notifications",
                now.minus(Duration.ofDays(properties.notificationTtlDays())),
                retentionRepositoryPort::deleteNotificationsOlderThan);
        int processedEvents = purge("processed events",
                now.minus(Duration.ofDays(properties.processedEventTtlDays())),
                retentionRepositoryPort::deleteProcessedEventsOlderThan);

        log.info("Retention pass: deleted {} notifications and {} processed-event rows",
                notifications, processedEvents);
        return new PurgeReport(notifications, processedEvents);
    }

    /**
     * Runs batches until one comes back short — meaning the backlog is clear —
     * or the per-run cap is hit, whichever is first.
     */
    private int purge(String what, Instant cutoff, DeleteBatch delete) {
        int batchSize = properties.batchSize();
        int total = 0;
        for (int pass = 0; pass < properties.maxBatchesPerRun(); pass++) {
            int deleted = delete.apply(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                return total;
            }
        }
        log.info("Retention: hit the {}-batch cap purging {} ({} removed); the rest waits for the next pass",
                properties.maxBatchesPerRun(), what, total);
        return total;
    }

    @FunctionalInterface
    private interface DeleteBatch {
        int apply(Instant cutoff, int batchSize);
    }
}
