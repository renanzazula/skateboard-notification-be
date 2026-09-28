package com.skateboard.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * The inbox queries. {@code user_notification} has no tenant column — the
 * tenant lives on {@code notification} — so every query joins or sub-selects
 * it rather than trusting user id alone. The entities carry no JPA
 * relationship, hence the theta joins.
 */
public interface SpringUserNotificationRepository extends JpaRepository<UserNotificationJpaEntity, UUID> {

    @Query("SELECT COUNT(un) FROM UserNotificationJpaEntity un, NotificationJpaEntity n "
            + "WHERE n.id = un.notificationId AND un.userId = :userId AND n.tenantId = :tenantId "
            + "AND un.readAt IS NULL")
    long countUnread(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(un) > 0 FROM UserNotificationJpaEntity un, NotificationJpaEntity n "
            + "WHERE n.id = un.notificationId AND un.userId = :userId AND n.tenantId = :tenantId "
            + "AND un.notificationId = :notificationId")
    boolean isRecipient(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                        @Param("notificationId") UUID notificationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserNotificationJpaEntity un SET un.readAt = :readAt "
            + "WHERE un.userId = :userId AND un.notificationId = :notificationId AND un.readAt IS NULL "
            + "AND un.notificationId IN (SELECT n.id FROM NotificationJpaEntity n WHERE n.tenantId = :tenantId)")
    int markRead(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                 @Param("notificationId") UUID notificationId, @Param("readAt") Instant readAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserNotificationJpaEntity un SET un.readAt = :readAt "
            + "WHERE un.userId = :userId AND un.readAt IS NULL AND un.createdAt <= :receivedUpTo "
            + "AND un.notificationId IN (SELECT n.id FROM NotificationJpaEntity n WHERE n.tenantId = :tenantId)")
    int markAllRead(@Param("userId") UUID userId, @Param("tenantId") UUID tenantId,
                    @Param("receivedUpTo") Instant receivedUpTo, @Param("readAt") Instant readAt);
}
