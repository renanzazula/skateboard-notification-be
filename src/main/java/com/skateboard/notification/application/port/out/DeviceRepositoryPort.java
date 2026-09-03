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
     * Disables every registration holding this push token except the one being
     * kept, and answers how many were closed.
     *
     * <p>A push token addresses a handset, so exactly one registration may own
     * it. Two cases produce a second one: somebody else signs in on a shared
     * device (spec §29), and the same person reinstalls under a new device
     * identifier — which, left alone, would deliver every notification to that
     * handset twice.
     *
     * <p>Expressed as a targeted update rather than load-mutate-save because
     * the rows being closed are not the caller's to rewrite: another request
     * may be updating them concurrently, and writing back a whole aggregate
     * would undo it.
     */
    int disableOtherRegistrationsForToken(String pushToken, UUID keepDeviceId);

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
}
