package com.skateboard.notification.adapter.in.rest;

import com.skateboard.notification.application.port.in.GetNotificationPreferencesUseCase;
import com.skateboard.notification.application.port.in.UpdateNotificationPreferencesUseCase;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.infrastructure.security.CurrentUser;
import com.skateboard.notification.infrastructure.security.CurrentUserProvider;
import com.skateboard.notification.infrastructure.web.api.PreferencesApi;
import com.skateboard.notification.infrastructure.web.dto.NotificationPreferences;
import com.skateboard.notification.infrastructure.web.dto.NotificationPreferencesResponse;
import com.skateboard.notification.infrastructure.web.dto.UpdateNotificationPreferencesRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;

/**
 * Notification preferences, taken over from skateboard-user-be.
 *
 * <p>The wire contract is unchanged from what that service served — same
 * nested {@code notifications} object, same two flags, same
 * FUNC_USER_SELF_READ/FUNC_USER_SELF_UPDATE authorities — so the BFF re-points
 * one route and the mobile settings screen is untouched. Internally the flags
 * are stored generically: {@code pushEnabled} is the master channel switch and
 * {@code newPodcastEnabled} is the NEW_PODCAST row, so a second notification
 * type needs a field here and nothing else.
 */
@RestController
public class NotificationPreferenceController implements PreferencesApi {

    private static final String SELF_READ = "hasAuthority('FUNC_USER_SELF_READ')";
    private static final String SELF_UPDATE = "hasAuthority('FUNC_USER_SELF_UPDATE')";

    private final GetNotificationPreferencesUseCase getNotificationPreferencesUseCase;
    private final UpdateNotificationPreferencesUseCase updateNotificationPreferencesUseCase;
    private final CurrentUserProvider currentUserProvider;

    public NotificationPreferenceController(
            GetNotificationPreferencesUseCase getNotificationPreferencesUseCase,
            UpdateNotificationPreferencesUseCase updateNotificationPreferencesUseCase,
            CurrentUserProvider currentUserProvider) {
        this.getNotificationPreferencesUseCase = getNotificationPreferencesUseCase;
        this.updateNotificationPreferencesUseCase = updateNotificationPreferencesUseCase;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    @PreAuthorize(SELF_READ)
    public ResponseEntity<NotificationPreferencesResponse> getNotificationPreferences() {
        CurrentUser caller = currentUserProvider.require();
        return ResponseEntity.ok(toResponse(
                getNotificationPreferencesUseCase.execute(caller.id(), caller.tenantId())));
    }

    @Override
    @PreAuthorize(SELF_UPDATE)
    public ResponseEntity<NotificationPreferencesResponse> updateNotificationPreferences(
            UpdateNotificationPreferencesRequest request) {
        CurrentUser caller = currentUserProvider.require();
        NotificationPreferences patch = request.getNotifications();

        Map<NotificationType, Boolean> byType = new EnumMap<>(NotificationType.class);
        if (patch != null && patch.getNewPodcastEnabled() != null) {
            byType.put(NotificationType.NEW_PODCAST, patch.getNewPodcastEnabled());
        }

        return ResponseEntity.ok(toResponse(updateNotificationPreferencesUseCase.execute(
                new UpdateNotificationPreferencesUseCase.Input(
                        caller.id(),
                        caller.tenantId(),
                        patch == null ? null : patch.getPushEnabled(),
                        byType))));
    }

    private NotificationPreferencesResponse toResponse(
            com.skateboard.notification.domain.model.NotificationPreferences preferences) {
        NotificationPreferences notifications = new NotificationPreferences()
                .pushEnabled(preferences.isPushEnabled())
                .newPodcastEnabled(preferences.isEnabledFor(NotificationType.NEW_PODCAST));
        return new NotificationPreferencesResponse()
                .notifications(notifications)
                .updatedAt(preferences.getUpdatedAt() == null
                        ? null : preferences.getUpdatedAt().atOffset(ZoneOffset.UTC));
    }
}
