package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.domain.model.PushProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class DevicePersistenceAdapter implements DeviceRepositoryPort {

    private final SpringNotificationDeviceRepository repository;

    public DevicePersistenceAdapter(SpringNotificationDeviceRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<NotificationDevice> findByUserAndIdentifier(UUID userId, String deviceIdentifier) {
        return repository.findByUserIdAndDeviceIdentifier(userId, deviceIdentifier).map(this::toDomain);
    }

    @Override
    public Optional<NotificationDevice> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public List<NotificationDevice> findOtherUsersWithPushToken(String pushToken, UUID excludedUserId) {
        return repository.findByPushTokenAndUserIdNot(pushToken, excludedUserId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<NotificationDevice> findNotifiableDevices(UUID tenantId, NotificationType type) {
        return repository.findNotifiableDevices(tenantId, type.name()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public NotificationDevice save(NotificationDevice device) {
        return toDomain(repository.save(toEntity(device)));
    }

    @Override
    @Transactional
    public void disableById(UUID id) {
        repository.disableById(id, Instant.now());
    }

    @Override
    @Transactional
    public List<NotificationDevice> saveAll(List<NotificationDevice> devices) {
        List<NotificationDeviceJpaEntity> entities = devices.stream().map(this::toEntity).toList();
        return repository.saveAll(entities).stream().map(this::toDomain).toList();
    }

    private NotificationDevice toDomain(NotificationDeviceJpaEntity entity) {
        return NotificationDevice.reconstitute(
                entity.getId(),
                entity.getUserId(),
                entity.getTenantId(),
                entity.getDeviceIdentifier(),
                DevicePlatform.valueOf(entity.getPlatform()),
                PushProvider.valueOf(entity.getPushProvider()),
                entity.getPushToken(),
                entity.getAppVersion(),
                entity.getDeviceName(),
                entity.isEnabled(),
                entity.getLastSeenAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private NotificationDeviceJpaEntity toEntity(NotificationDevice device) {
        NotificationDeviceJpaEntity entity = new NotificationDeviceJpaEntity();
        entity.setId(device.getId());
        entity.setUserId(device.getUserId());
        entity.setTenantId(device.getTenantId());
        entity.setDeviceIdentifier(device.getDeviceIdentifier());
        entity.setPlatform(device.getPlatform().name());
        entity.setPushProvider(device.getPushProvider().name());
        entity.setPushToken(device.getPushToken());
        entity.setAppVersion(device.getAppVersion());
        entity.setDeviceName(device.getDeviceName());
        entity.setEnabled(device.isEnabled());
        entity.setLastSeenAt(device.getLastSeenAt());
        entity.setCreatedAt(device.getCreatedAt());
        entity.setUpdatedAt(device.getUpdatedAt());
        return entity;
    }
}
