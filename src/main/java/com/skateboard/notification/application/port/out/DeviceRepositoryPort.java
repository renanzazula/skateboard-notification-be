package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepositoryPort {

    Optional<NotificationDevice> findByUserAndIdentifier(UUID userId, String deviceIdentifier);

    Optional<NotificationDevice> findById(UUID id);

    /**
     * Every other registration of the same handset — used to disable the
     * previous account's registration when someone else signs in on a shared
     * device (spec §29).
     */
    List<NotificationDevice> findOtherUsersWithPushToken(String pushToken, UUID excludedUserId);

    /**
     * The fan-out query: every device that should receive a notification of
     * this type in this tenant, with the master switch and the per-type
     * preference already applied.
     *
     * <p>Tenant scoping is part of the port's contract rather than something
     * callers remember to add, because forgetting it once leaks one tenant's
     * notification to another's users.
     */
    List<NotificationDevice> findNotifiableDevices(UUID tenantId, NotificationType type);

    NotificationDevice save(NotificationDevice device);

    List<NotificationDevice> saveAll(List<NotificationDevice> devices);
}
