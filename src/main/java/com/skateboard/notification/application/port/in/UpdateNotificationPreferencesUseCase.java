package com.skateboard.notification.application.port.in;

import com.skateboard.notification.domain.model.NotificationPreferences;
import com.skateboard.notification.domain.model.NotificationType;

import java.util.Map;
import java.util.UUID;

public interface UpdateNotificationPreferencesUseCase {

    /** Null values mean "leave unchanged" — these are partial updates. */
    record Input(UUID userId,
                 UUID tenantId,
                 Boolean pushEnabled,
                 Map<NotificationType, Boolean> byType) {
    }

    NotificationPreferences execute(Input input);
}
