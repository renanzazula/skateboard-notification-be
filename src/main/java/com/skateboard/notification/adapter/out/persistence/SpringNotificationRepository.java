package com.skateboard.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface SpringNotificationRepository extends JpaRepository<NotificationJpaEntity, UUID> {

    /**
     * One retention batch. The inner {@code LIMIT} keeps a single statement
     * from locking the whole tail of the table; the caller loops. Ordered so
     * batches walk the {@code idx_notification_created_at} index oldest-first
     * ({@code idx_notification_tenant_created_at} can't serve this — it leads
     * with {@code tenant_id}).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM notification
            WHERE id IN (
                SELECT id FROM notification WHERE created_at < :cutoff
                ORDER BY created_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
