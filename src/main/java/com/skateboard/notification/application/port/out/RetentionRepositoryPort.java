package com.skateboard.notification.application.port.out;

import java.time.Instant;

/**
 * Bounded, batched deletion of rows the service no longer needs (spec §40).
 *
 * <p>Four tables grow without bound — {@code notification},
 * {@code user_notification}, {@code notification_delivery} and
 * {@code processed_event} — and the platform has no archival story. The
 * idempotency ledger is the worst of them: pure bookkeeping with no value once
 * a redelivery can no longer arrive.
 *
 * <p>Each method deletes at most {@code batchSize} rows and returns how many it
 * removed, so the caller can loop until the backlog is clear without ever
 * holding a delete lock over a large range. Implementations run each batch in
 * its own transaction.
 */
public interface RetentionRepositoryPort {

    /**
     * Deletes up to {@code batchSize} notifications created before
     * {@code cutoff}. Their {@code user_notification} and
     * {@code notification_delivery} rows go with them by {@code ON DELETE
     * CASCADE}.
     */
    int deleteNotificationsOlderThan(Instant cutoff, int batchSize);

    /**
     * Deletes up to {@code batchSize} idempotency-ledger rows recorded before
     * {@code cutoff}.
     */
    int deleteProcessedEventsOlderThan(Instant cutoff, int batchSize);
}
