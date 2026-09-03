package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.UpdateNotificationPreferencesUseCase;
import com.skateboard.notification.application.port.out.PreferenceRepositoryPort;
import com.skateboard.notification.domain.model.NotificationPreferences;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpdateNotificationPreferencesService implements UpdateNotificationPreferencesUseCase {

    private final PreferenceRepositoryPort preferenceRepositoryPort;

    public UpdateNotificationPreferencesService(PreferenceRepositoryPort preferenceRepositoryPort) {
        this.preferenceRepositoryPort = preferenceRepositoryPort;
    }

    @Override
    @Transactional
    public NotificationPreferences execute(Input input) {
        NotificationPreferences preferences =
                preferenceRepositoryPort.load(input.userId(), input.tenantId());
        preferences.update(input.pushEnabled(), input.byType());
        preferenceRepositoryPort.save(preferences);
        return preferences;
    }
}
