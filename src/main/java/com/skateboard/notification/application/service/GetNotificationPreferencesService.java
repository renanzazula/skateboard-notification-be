package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.GetNotificationPreferencesUseCase;
import com.skateboard.notification.application.port.out.PreferenceRepositoryPort;
import com.skateboard.notification.domain.model.NotificationPreferences;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GetNotificationPreferencesService implements GetNotificationPreferencesUseCase {

    private final PreferenceRepositoryPort preferenceRepositoryPort;

    public GetNotificationPreferencesService(PreferenceRepositoryPort preferenceRepositoryPort) {
        this.preferenceRepositoryPort = preferenceRepositoryPort;
    }

    /**
     * Read-only on purpose: a user opening the settings screen must not create
     * preference rows. Absence already means enabled, so writing defaults
     * would add rows that say nothing.
     */
    @Override
    @Transactional(readOnly = true)
    public NotificationPreferences execute(UUID userId, UUID tenantId) {
        return preferenceRepositoryPort.load(userId, tenantId);
    }
}
