package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.NotificationPreferences;

import java.util.UUID;

public interface PreferenceRepositoryPort {

    /**
     * Never empty: a user with no stored rows gets
     * {@link NotificationPreferences#defaults}, because absence means enabled.
     * Returning Optional here would push that decision onto every caller.
     */
    NotificationPreferences load(UUID userId, UUID tenantId);

    void save(NotificationPreferences preferences);
}
