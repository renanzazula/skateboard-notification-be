package com.skateboard.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringEmailDeliveryRepository extends JpaRepository<EmailDeliveryJpaEntity, UUID> {

    /** Mirrors {@code SpringNotificationDeliveryRepository.lockRetryable} — see its javadoc. */
    @Query(value = """
            SELECT d.* FROM email_delivery d
            WHERE d.status = 'PENDING'
              AND d.attempt_count < :maxAttempts
              AND (d.last_attempt_at IS NULL OR d.last_attempt_at < :notAttemptedSince)
            ORDER BY d.created_at, d.id
            LIMIT :maxRows
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<EmailDeliveryJpaEntity> lockRetryable(@Param("maxAttempts") int maxAttempts,
                                                @Param("notAttemptedSince") Instant notAttemptedSince,
                                                @Param("maxRows") int maxRows);

    long countByStatus(String status);
}
