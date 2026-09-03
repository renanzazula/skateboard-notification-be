package com.skateboard.notification.infrastructure.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds on the retention pass (spec §40).
 *
 * @param enabled              whether the pass runs at all
 * @param notificationTtlDays  a notification, and the {@code user_notification}
 *                             and {@code notification_delivery} rows that cascade
 *                             from it, is deleted once older than this. Generous,
 *                             because the Phase 2 inbox history API reads this
 *                             table — revisit when it ships
 * @param processedEventTtlDays how long an idempotency-ledger row is kept. It
 *                             only has to outlast the longest possible
 *                             redelivery, and the AMQP retry window is minutes,
 *                             so a week is already far more than enough
 * @param batchSize            rows deleted per statement, so a purge never locks
 *                             a large range at once
 * @param maxBatchesPerRun     hard cap on batches in one pass, so a big first
 *                             backlog drains over several nights rather than
 *                             holding the job for minutes
 */
@ConfigurationProperties(prefix = "retention")
public record RetentionProperties(boolean enabled,
                                  int notificationTtlDays,
                                  int processedEventTtlDays,
                                  int batchSize,
                                  int maxBatchesPerRun) {
}
