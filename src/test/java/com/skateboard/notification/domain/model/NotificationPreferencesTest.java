package com.skateboard.notification.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default-to-enabled rule is what makes taking preferences over from
 * skateboard-user-be a no-op for existing users, so it is asserted directly
 * rather than only through the query that relies on it.
 */
class NotificationPreferencesTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void aUserWithNoStoredPreferencesIsNotifiable() {
        NotificationPreferences preferences = NotificationPreferences.defaults(USER, TENANT);

        assertThat(preferences.isPushEnabled()).isTrue();
        assertThat(preferences.allows(NotificationType.NEW_PODCAST)).isTrue();
    }

    @Test
    void theMasterSwitchOverridesAnEnabledType() {
        NotificationPreferences preferences = NotificationPreferences.defaults(USER, TENANT);
        preferences.update(false, Map.of(NotificationType.NEW_PODCAST, true));

        assertThat(preferences.allows(NotificationType.NEW_PODCAST)).isFalse();
    }

    @Test
    void aDisabledTypeIsBlockedEvenWhileTheMasterSwitchIsOn() {
        NotificationPreferences preferences = NotificationPreferences.defaults(USER, TENANT);
        preferences.update(true, Map.of(NotificationType.NEW_PODCAST, false));

        assertThat(preferences.allows(NotificationType.NEW_PODCAST)).isFalse();
        assertThat(preferences.allows(NotificationType.NEW_POST)).isTrue();
    }

    @Test
    void nullFieldsLeaveTheExistingValueAlone() {
        NotificationPreferences preferences = NotificationPreferences.defaults(USER, TENANT);
        preferences.update(false, Map.of(NotificationType.NEW_PODCAST, false));

        Map<NotificationType, Boolean> noTypeChanges = new HashMap<>();
        noTypeChanges.put(NotificationType.NEW_PODCAST, null);
        preferences.update(null, noTypeChanges);

        assertThat(preferences.isPushEnabled()).isFalse();
        assertThat(preferences.isEnabledFor(NotificationType.NEW_PODCAST)).isFalse();
    }

    @Test
    void updatingRecordsWhenItHappened() {
        NotificationPreferences preferences = NotificationPreferences.defaults(USER, TENANT);
        assertThat(preferences.getUpdatedAt()).isNull();

        preferences.update(true, Map.of());

        assertThat(preferences.getUpdatedAt()).isNotNull();
    }
}
