package com.skateboard.notification.application.port.in;

import com.skateboard.notification.domain.model.NotificationPreferences;

import java.util.UUID;

public interface GetNotificationPreferencesUseCase {

    NotificationPreferences execute(UUID userId, UUID tenantId);
}
