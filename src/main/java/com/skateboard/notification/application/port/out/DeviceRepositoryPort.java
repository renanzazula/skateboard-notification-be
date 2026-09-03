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

    /**
     * Disables one device by id, without writing back a whole aggregate.
     *
     * <p>Dispatch learns a token is dead only after the provider answers, by
     * which time the device snapshot it loaded may be stale — the owner could
     * have re-registered mid-fan-out. Saving that snapshot would revert their
     * new token and disable a live device, so the one field that must change
     * is changed on its own.
     */
    void disableById(UUID id);

    List<NotificationDevice> saveAll(List<NotificationDevice> devices);
}
