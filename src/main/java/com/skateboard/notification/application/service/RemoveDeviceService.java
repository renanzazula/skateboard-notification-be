package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.RemoveDeviceUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RemoveDeviceService implements RemoveDeviceUseCase {

    private final DeviceRepositoryPort deviceRepositoryPort;

    public RemoveDeviceService(DeviceRepositoryPort deviceRepositoryPort) {
        this.deviceRepositoryPort = deviceRepositoryPort;
    }

    @Override
    @Transactional
    public void execute(UUID userId, String deviceIdentifier) {
        deviceRepositoryPort.findByUserAndIdentifier(userId, deviceIdentifier)
                .ifPresent(device -> {
                    device.disable();
                    deviceRepositoryPort.save(device);
                });
    }
}
