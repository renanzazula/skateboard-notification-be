package com.skateboard.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringNotificationDeliveryRepository
        extends JpaRepository<NotificationDeliveryJpaEntity, UUID> {

    /**
     * Deliveries still owed a send, locked so a second instance polling the
     * same window cannot pick up the same ones.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} rather than a scheduler lock: it needs
     * no extra table or dependency, and it lets two instances share a backlog
     * instead of one idling while the other works through it. Rows another
     * transaction already holds are skipped rather than waited on, so a slow
     * sender never blocks the poll.
     *
     * <p>The {@code last_attempt_at IS NULL} arm covers a delivery whose very
     * first send never happened — the process died between committing the
     * batch and calling the provider.
     */
    @Query(value = """
            SELECT d.* FROM notification_delivery d
            WHERE d.status = 'PENDING'
              AND d.attempt_count < :maxAttempts
              AND (d.last_attempt_at IS NULL OR d.last_attempt_at < :notAttemptedSince)
            ORDER BY d.created_at, d.id
            LIMIT :maxRows
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationDeliveryJpaEntity> lockRetryable(@Param("maxAttempts") int maxAttempts,
                                                       @Param("notAttemptedSince") Instant notAttemptedSince,
                                                       @Param("maxRows") int maxRows);

    /** Served by {@code idx_notification_delivery_status}. Backs the pending-backlog gauge. */
    long countByStatus(String status);

    /**
     * Accepted-but-unconfirmed deliveries, oldest first.
     *
     * <p>No row lock here, unlike the retry claim: reading a receipt has no
     * external side effect, so two instances doing it concurrently is wasteful
     * at worst, never a double send. The status transition it drives is
     * idempotent.
     */
    @Query(value = """
            SELECT d.* FROM notification_delivery d
            WHERE d.status = 'SENT'
              AND d.provider_message_id IS NOT NULL
              AND d.last_attempt_at > :sentAfter
              AND d.last_attempt_at < :sentBefore
            ORDER BY d.last_attempt_at, d.id
            LIMIT :maxRows
            """, nativeQuery = true)
    List<NotificationDeliveryJpaEntity> findAwaitingReceipt(@Param("sentAfter") Instant sentAfter,
                                                             @Param("sentBefore") Instant sentBefore,
                                                             @Param("maxRows") int maxRows);
}
