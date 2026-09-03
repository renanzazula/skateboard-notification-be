package com.skateboard.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringNotificationDeviceRepository extends JpaRepository<NotificationDeviceJpaEntity, UUID> {

    Optional<NotificationDeviceJpaEntity> findByUserIdAndDeviceIdentifier(UUID userId, String deviceIdentifier);

    List<NotificationDeviceJpaEntity> findByPushTokenAndUserIdNot(String pushToken, UUID userId);

    /**
     * The fan-out query. Preferences are applied here rather than in Java
     * because loading every device in the tenant to filter most of them out
     * would not survive a real audience.
     *
     * <p>Both preference joins are left joins with a COALESCE default of true:
     * a user who never opened the settings screen has no rows and must still
     * be notified, matching the DEFAULT TRUE this preference had while
     * skateboard-user-be owned it. The tenant predicate is not optional — it
     * is the isolation guarantee.
     */
    @Query(value = """
            SELECT d.* FROM notification_device d
            LEFT JOIN notification_channel_setting cs ON cs.user_id = d.user_id
            LEFT JOIN notification_preference p
                   ON p.user_id = d.user_id AND p.notification_type = :notificationType
            WHERE d.tenant_id = :tenantId
              AND d.enabled = TRUE
              AND COALESCE(cs.push_enabled, TRUE) = TRUE
              AND COALESCE(p.push_enabled, TRUE) = TRUE
            ORDER BY d.created_at, d.id
            """, nativeQuery = true)
    List<NotificationDeviceJpaEntity> findNotifiableDevices(@Param("tenantId") UUID tenantId,
                                                             @Param("notificationType") String notificationType);
}
