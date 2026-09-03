package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.RegisterDeviceUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.domain.model.NotificationDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        releaseTokenFromOtherUsers(input);

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

        return deviceRepositoryPort.save(device);
    }

    /**
     * A push token identifies a handset, not an account. If user B signs in on
     * a phone user A was signed into, both registrations would otherwise stay
     * live and user A's notifications would keep landing on a phone they no
     * longer hold (spec §29). The client is expected to de-register on logout;
     * this covers the case where it could not — an involuntary sign-out, a
     * crash, an uninstall/reinstall.
     */
    private void releaseTokenFromOtherUsers(Input input) {
        List<NotificationDevice> strays =
                deviceRepositoryPort.findOtherUsersWithPushToken(input.pushToken(), input.userId());
        if (strays.isEmpty()) {
            return;
        }
        strays.forEach(NotificationDevice::disable);
        deviceRepositoryPort.saveAll(strays);
        log.info("Disabled {} stale device registration(s) holding the push token now claimed by user {}",
                strays.size(), input.userId());
    }
}
