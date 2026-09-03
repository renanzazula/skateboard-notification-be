package com.skateboard.notification.application.port.in;

import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.PushProvider;

import java.util.UUID;

public interface RegisterDeviceUseCase {

    record Input(UUID userId,
                 UUID tenantId,
                 String deviceIdentifier,
                 DevicePlatform platform,
                 PushProvider provider,
                 String pushToken,
                 String appVersion,
                 String deviceName) {
    }

    NotificationDevice execute(Input input);
}
