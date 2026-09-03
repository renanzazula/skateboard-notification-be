package com.skateboard.notification.domain.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * A user's complete answer to "what may we push to you", assembled from the
 * master switch and the per-type opt-outs.
 *
 * <p>Absence means enabled at every level: a user with no stored rows is
 * notifiable. That is not a shortcut — it reproduces the DEFAULT TRUE this
 * preference carried while skateboard-user-be owned it, so taking the feature
 * over changes nobody's settings.
 */
public class NotificationPreferences {

    private final UUID userId;
    private final UUID tenantId;
    private boolean pushEnabled;
    private final Map<NotificationType, Boolean> byType;
    private Instant updatedAt;

    private NotificationPreferences(UUID userId, UUID tenantId, boolean pushEnabled,
                                     Map<NotificationType, Boolean> byType, Instant updatedAt) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.pushEnabled = pushEnabled;
        this.byType = new EnumMap<>(byType);
        this.updatedAt = updatedAt;
    }

    /** What a user who has never opened the settings screen gets. */
    public static NotificationPreferences defaults(UUID userId, UUID tenantId) {
        return new NotificationPreferences(userId, tenantId, true, new EnumMap<>(NotificationType.class), null);
    }

    public static NotificationPreferences reconstitute(UUID userId, UUID tenantId, boolean pushEnabled,
                                                        Map<NotificationType, Boolean> byType, Instant updatedAt) {
        return new NotificationPreferences(userId, tenantId, pushEnabled, byType, updatedAt);
    }

    /** Null means "leave unchanged" — these are partial updates (spec §13). */
    public void update(Boolean pushEnabled, Map<NotificationType, Boolean> typeChanges) {
        if (pushEnabled != null) {
            this.pushEnabled = pushEnabled;
        }
        if (typeChanges != null) {
            typeChanges.forEach((type, enabled) -> {
                if (enabled != null) {
                    this.byType.put(type, enabled);
                }
            });
        }
        this.updatedAt = Instant.now();
    }

    /**
     * The question recipient resolution actually asks. Both gates must be
     * open: the master switch off means no push of any kind, regardless of
     * what the per-type flags say.
     */
    public boolean allows(NotificationType type) {
        return pushEnabled && byType.getOrDefault(type, true);
    }

    public UUID getUserId()      { return userId; }
    public UUID getTenantId()    { return tenantId; }
    public boolean isPushEnabled() { return pushEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean isEnabledFor(NotificationType type) {
        return byType.getOrDefault(type, true);
    }

    public Map<NotificationType, Boolean> getByType() {
        return new EnumMap<>(byType);
    }
}
