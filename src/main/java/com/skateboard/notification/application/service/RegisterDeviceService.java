package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.RegisterDeviceUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.domain.model.NotificationDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterDeviceService implements RegisterDeviceUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterDeviceService.class);

    private final DeviceRepositoryPort deviceRepositoryPort;

    public RegisterDeviceService(DeviceRepositoryPort deviceRepositoryPort) {
        this.deviceRepositoryPort = deviceRepositoryPort;
    }

    @Override
    @Transactional
    public NotificationDevice execute(Input input) {
        NotificationDevice device = deviceRepositoryPort
                .findByUserAndIdentifier(input.userId(), input.deviceIdentifier())
                .map(existing -> {
                    existing.refresh(input.platform(), input.provider(), input.pushToken(),
                            input.appVersion(), input.deviceName());
                    return existing;
                })
                .orElseGet(() -> NotificationDevice.register(input.userId(), input.tenantId(),
                        input.deviceIdentifier(), input.platform(), input.provider(), input.pushToken(),
                        input.appVersion(), input.deviceName()));

        NotificationDevice saved = deviceRepositoryPort.save(device);
        releaseTokenFromOtherRegistrations(input, saved);
        return saved;
    }

    /**
     * A push token addresses a handset, not an account, so exactly one
     * registration may own it — and the registration that just claimed it wins.
     *
     * <p>Two situations produce a rival. Somebody else signs in on a shared
     * device, and their notifications would otherwise keep arriving for the
     * previous account (spec §29); the client is expected to de-register on
     * logout, but cannot when the sign-out was involuntary, or the app crashed,
     * or it was reinstalled. And the *same* person reinstalls, getting a fresh
     * device identifier for the same token — two live rows, and every
     * notification delivered to that handset twice.
     *
     * <p>Done after the save so the surviving row can be excluded by id, and as
     * a targeted update so a concurrent registration of one of those other
     * rows is not overwritten by a stale snapshot.
     */
    private void releaseTokenFromOtherRegistrations(Input input, NotificationDevice saved) {
        int released = deviceRepositoryPort
                .disableOtherRegistrationsForToken(input.pushToken(), saved.getId());
        if (released > 0) {
            log.info("Disabled {} stale registration(s) of the push token now claimed by device {}",
                    released, saved.getId());
        }
    }
}
