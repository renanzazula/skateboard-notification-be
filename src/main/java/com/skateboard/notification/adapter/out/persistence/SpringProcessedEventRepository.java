package com.skateboard.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface SpringProcessedEventRepository extends JpaRepository<ProcessedEventJpaEntity, UUID> {

    /**
     * One retention batch off the idempotency ledger, walking
     * {@code idx_processed_event_processed_at} oldest-first. The inner
     * {@code LIMIT} bounds the lock; the caller loops.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            DELETE FROM processed_event
            WHERE event_id IN (
                SELECT event_id FROM processed_event WHERE processed_at < :cutoff
                ORDER BY processed_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
