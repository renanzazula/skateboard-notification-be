package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.PreferenceRepositoryPort;
import com.skateboard.notification.domain.model.NotificationPreferences;
import com.skateboard.notification.domain.model.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Assembles the two preference tables into one aggregate and writes it back.
 *
 * <p>Rows are created lazily on write, never on read: absence already means
 * enabled, so a read that materialised defaults would fill the table with rows
 * that carry no information.
 *
 * <p>Both tables key on {@code user_id} alone, deliberately. The id is the
 * Keycloak subject, which is unique across the whole realm rather than per
 * tenant, so a user has exactly one preference row and adding tenant to the key
 * would only invent a second one that nothing could reach. {@code tenant_id} is
 * still carried and still refreshed on write — the JWT is authoritative for
 * which tenant the caller belongs to — so that the recipient query can join on
 * it and stay consistent with the device rows it filters.
 */
@Component
public class PreferencePersistenceAdapter implements PreferenceRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(PreferencePersistenceAdapter.class);

    private final SpringNotificationChannelSettingRepository channelSettingRepository;
    private final SpringNotificationPreferenceRepository preferenceRepository;

    public PreferencePersistenceAdapter(SpringNotificationChannelSettingRepository channelSettingRepository,
                                         SpringNotificationPreferenceRepository preferenceRepository) {
        this.channelSettingRepository = channelSettingRepository;
        this.preferenceRepository = preferenceRepository;
    }

    @Override
    public NotificationPreferences load(UUID userId, UUID tenantId) {
        Optional<NotificationChannelSettingJpaEntity> setting = channelSettingRepository.findById(userId);
        boolean pushEnabled = setting.map(NotificationChannelSettingJpaEntity::isPushEnabled).orElse(true);
        Instant updatedAt = setting.map(NotificationChannelSettingJpaEntity::getUpdatedAt).orElse(null);

        Map<NotificationType, Boolean> byType = new EnumMap<>(NotificationType.class);

        for (NotificationPreferenceJpaEntity entity : preferenceRepository.findByUserId(userId)) {
            NotificationType type = parseType(entity.getNotificationType());
            if (type == null) {
                continue;
            }
            byType.put(type, entity.isPushEnabled());
            if (updatedAt == null || entity.getUpdatedAt().isAfter(updatedAt)) {
                updatedAt = entity.getUpdatedAt();
            }
        }

        return NotificationPreferences.reconstitute(userId, tenantId, pushEnabled, byType, updatedAt);
    }

    @Override
    @Transactional
    public void save(NotificationPreferences preferences) {
        Instant now = preferences.getUpdatedAt() == null ? Instant.now() : preferences.getUpdatedAt();

        NotificationChannelSettingJpaEntity setting = channelSettingRepository
                .findById(preferences.getUserId())
                .orElseGet(() -> {
                    NotificationChannelSettingJpaEntity fresh = new NotificationChannelSettingJpaEntity();
                    fresh.setUserId(preferences.getUserId());
                    fresh.setCreatedAt(now);
                    return fresh;
                });
        setting.setTenantId(preferences.getTenantId());
        setting.setPushEnabled(preferences.isPushEnabled());
        setting.setUpdatedAt(now);
        channelSettingRepository.save(setting);

        preferences.getByType().forEach((type, enabled) -> {
            NotificationPreferenceJpaEntity entity = preferenceRepository
                    .findByUserIdAndNotificationType(preferences.getUserId(), type.name())
                    .orElseGet(() -> {
                        NotificationPreferenceJpaEntity fresh = new NotificationPreferenceJpaEntity();
                        fresh.setId(UUID.randomUUID());
                        fresh.setUserId(preferences.getUserId());
                        fresh.setNotificationType(type.name());
                        fresh.setCreatedAt(now);
                        return fresh;
                    });
            entity.setTenantId(preferences.getTenantId());
            entity.setPushEnabled(enabled);
            entity.setUpdatedAt(now);
            preferenceRepository.save(entity);
        });
    }

    /**
     * A stored type this build no longer knows about is skipped rather than
     * fatal: rolling back a release that introduced a type must not break the
     * settings screen for everyone who toggled it.
     */
    private NotificationType parseType(String value) {
        try {
            return NotificationType.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unknown stored notification type '{}'", value);
            return null;
        }
    }
}
