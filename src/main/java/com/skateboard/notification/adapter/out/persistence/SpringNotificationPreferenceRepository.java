package com.skateboard.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringNotificationPreferenceRepository
        extends JpaRepository<NotificationPreferenceJpaEntity, UUID> {

    List<NotificationPreferenceJpaEntity> findByUserId(UUID userId);

    Optional<NotificationPreferenceJpaEntity> findByUserIdAndNotificationType(UUID userId,
                                                                              String notificationType);
}
