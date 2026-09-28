package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.InboxRepositoryPort;
import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
import com.skateboard.notification.application.port.out.PreferenceRepositoryPort;
import com.skateboard.notification.application.port.out.ProcessedEventPort;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.InboxEntry;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationPreferences;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.domain.model.PushProvider;
import com.skateboard.notification.domain.model.UserNotification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the fan-out query and the idempotency ledger against a real
 * Postgres, because both depend on behaviour H2 does not reproduce: the
 * COALESCE-over-left-join that makes a missing preference mean "enabled", and
 * a primary-key collision decided by the database rather than by a read.
 *
 * <p>Rabbit is not started here — the listener is switched off so the context
 * boots without a broker. What the queue does with a message is
 * {@link com.skateboard.notification.adapter.in.messaging.PodcastPublishedEventListenerTest}'s
 * job; this is about what the tables do.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "app.security.oauth2.audience=skateboard-notification-be",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // Background jobs are all default-on. Left running inside a test they
        // fire mid-assertion, mutate the rows under it, and — for the retry pass,
        // which has no fake provider here — would reach out to the real Expo API.
        "push.retry.enabled=false",
        "push.receipts.enabled=false",
        "retention.enabled=false",
        "messaging.dead-letter.monitor-enabled=false"
})
@Testcontainers
class NotificationPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Autowired private DeviceRepositoryPort deviceRepositoryPort;
    @Autowired private PreferenceRepositoryPort preferenceRepositoryPort;
    @Autowired private ProcessedEventPort processedEventPort;
    @Autowired private NotificationRepositoryPort notificationRepositoryPort;
    @Autowired private InboxRepositoryPort inboxRepositoryPort;

    @Test
    void aUserWithNoStoredPreferencesIsStillNotifiable() {
        UUID user = registerDevice(TENANT_A).getUserId();

        assertThat(deviceRepositoryPort.findNotifiableDevices(TENANT_A, NotificationType.NEW_PODCAST))
                .extracting(NotificationDevice::getUserId)
                .contains(user);
    }

    /**
     * The isolation guarantee. A notification raised for one tenant must never
     * reach another's devices, however few tenants exist today.
     */
    @Test
    void aDeviceInAnotherTenantIsNeverATargetOfThisTenantsNotification() {
        NotificationDevice tenantBDevice = registerDevice(TENANT_B);

        assertThat(deviceRepositoryPort.findNotifiableDevices(TENANT_A, NotificationType.NEW_PODCAST))
                .extracting(NotificationDevice::getId)
                .doesNotContain(tenantBDevice.getId());
    }

    @Test
    void turningTheMasterSwitchOffRemovesEveryDeviceOfThatUser() {
        NotificationDevice device = registerDevice(TENANT_A);
        savePreferences(device.getUserId(), false, null);

        assertThat(deviceRepositoryPort.findNotifiableDevices(TENANT_A, NotificationType.NEW_PODCAST))
                .extracting(NotificationDevice::getId)
                .doesNotContain(device.getId());
    }

    @Test
    void optingOutOfOneTypeLeavesTheUserNotifiableForOthers() {
        NotificationDevice device = registerDevice(TENANT_A);
        savePreferences(device.getUserId(), true, Map.of(NotificationType.NEW_PODCAST, false));

        assertThat(deviceRepositoryPort.findNotifiableDevices(TENANT_A, NotificationType.NEW_PODCAST))
                .extracting(NotificationDevice::getId)
                .doesNotContain(device.getId());
        assertThat(deviceRepositoryPort.findNotifiableDevices(TENANT_A, NotificationType.NEW_POST))
                .extracting(NotificationDevice::getId)
                .contains(device.getId());
    }

    @Test
    void aDisabledDeviceIsNotATarget() {
        NotificationDevice device = registerDevice(TENANT_A);
        device.disable();
        deviceRepositoryPort.save(device);

        assertThat(deviceRepositoryPort.findNotifiableDevices(TENANT_A, NotificationType.NEW_PODCAST))
                .extracting(NotificationDevice::getId)
                .doesNotContain(device.getId());
    }

    @Test
    void readingPreferencesForAnUnknownUserReturnsDefaultsWithoutWritingRows() {
        UUID unknown = UUID.randomUUID();

        NotificationPreferences preferences = preferenceRepositoryPort.load(unknown, TENANT_A);

        assertThat(preferences.isPushEnabled()).isTrue();
        assertThat(preferences.allows(NotificationType.NEW_PODCAST)).isTrue();
        assertThat(preferences.getUpdatedAt()).isNull();
    }

    @Test
    void preferencesSurviveARoundTrip() {
        UUID user = UUID.randomUUID();
        savePreferences(user, true, Map.of(NotificationType.NEW_PODCAST, false));

        NotificationPreferences reloaded = preferenceRepositoryPort.load(user, TENANT_A);

        assertThat(reloaded.isPushEnabled()).isTrue();
        assertThat(reloaded.isEnabledFor(NotificationType.NEW_PODCAST)).isFalse();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void anEventIdCanOnlyBeClaimedOnce() {
        UUID eventId = UUID.randomUUID();

        assertThat(processedEventPort.claim(eventId, "PODCAST_PUBLISHED")).isTrue();
        assertThat(processedEventPort.claim(eventId, "PODCAST_PUBLISHED")).isFalse();
    }

    @Test
    void reRegisteringTheSameInstallDoesNotCreateASecondRow() {
        UUID user = UUID.randomUUID();
        NotificationDevice first = deviceRepositoryPort.save(NotificationDevice.register(user, TENANT_A,
                "install-1", DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[a]", "1.0.0", "iPhone"));

        first.refresh(DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[b]", "1.5.0", "iPhone");
        deviceRepositoryPort.save(first);

        assertThat(deviceRepositoryPort.findByUserAndIdentifier(user, "install-1"))
                .get()
                .satisfies(device -> {
                    assertThat(device.getId()).isEqualTo(first.getId());
                    assertThat(device.getPushToken()).isEqualTo("ExponentPushToken[b]");
                });
    }

    /** Muting stops the push, not the inbox: the audience ignores preferences. */
    @Test
    void theInboxAudienceIncludesUsersWhoMutedTheType() {
        UUID user = registerDevice(TENANT_A).getUserId();
        savePreferences(user, false, null);

        assertThat(deviceRepositoryPort.findUsersWithEnabledDevices(TENANT_A)).contains(user);
        assertThat(deviceRepositoryPort.findNotifiableDevices(TENANT_A, NotificationType.NEW_PODCAST))
                .extracting(NotificationDevice::getUserId)
                .doesNotContain(user);
    }

    @Test
    void theInboxListsNewestFirstAndPagesStably() throws InterruptedException {
        UUID user = UUID.randomUUID();
        Notification older = deliverTo(TENANT_A, user, "older");
        Thread.sleep(5);
        Notification newer = deliverTo(TENANT_A, user, "newer");

        assertThat(inboxRepositoryPort.findPage(user, TENANT_A, 0, 10))
                .extracting(entry -> entry.notification().getId())
                .containsExactly(newer.getId(), older.getId());
        assertThat(inboxRepositoryPort.findPage(user, TENANT_A, 1, 10))
                .extracting(entry -> entry.notification().getId())
                .containsExactly(older.getId());
    }

    @Test
    void theInboxNeverShowsAnotherTenantsNotification() {
        UUID user = UUID.randomUUID();
        deliverTo(TENANT_B, user, "elsewhere");

        assertThat(inboxRepositoryPort.findPage(user, TENANT_A, 0, 10)).isEmpty();
        assertThat(inboxRepositoryPort.countUnread(user, TENANT_A)).isZero();
    }

    @Test
    void markingReadChangesTheCountOnceAndKeepsTheFirstReadTime() {
        UUID user = UUID.randomUUID();
        Notification notification = deliverTo(TENANT_A, user, "one");
        deliverTo(TENANT_A, user, "two");
        assertThat(inboxRepositoryPort.countUnread(user, TENANT_A)).isEqualTo(2);

        Instant firstRead = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(inboxRepositoryPort.markRead(user, TENANT_A, notification.getId(), firstRead)).isEqualTo(1);
        assertThat(inboxRepositoryPort.markRead(user, TENANT_A, notification.getId(), Instant.now())).isZero();

        assertThat(inboxRepositoryPort.countUnread(user, TENANT_A)).isEqualTo(1);
        assertThat(inboxRepositoryPort.findPage(user, TENANT_A, 0, 10))
                .filteredOn(entry -> entry.notification().getId().equals(notification.getId()))
                .singleElement()
                .extracting(InboxEntry::readAt)
                .isEqualTo(firstRead);
    }

    @Test
    void aUserCannotMarkSomeoneElsesNotificationRead() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Notification notification = deliverTo(TENANT_A, owner, "private");

        assertThat(inboxRepositoryPort.markRead(stranger, TENANT_A, notification.getId(), Instant.now())).isZero();
        assertThat(inboxRepositoryPort.isRecipient(stranger, TENANT_A, notification.getId())).isFalse();
        assertThat(inboxRepositoryPort.isRecipient(owner, TENANT_A, notification.getId())).isTrue();
        assertThat(inboxRepositoryPort.countUnread(owner, TENANT_A)).isEqualTo(1);
    }

    @Test
    void readAllLeavesNotificationsThatArrivedAfterTheCutOff() throws InterruptedException {
        UUID user = UUID.randomUUID();
        deliverTo(TENANT_A, user, "seen");
        Thread.sleep(5);
        Instant loadedAt = Instant.now();
        Thread.sleep(5);
        deliverTo(TENANT_A, user, "arrived later");

        assertThat(inboxRepositoryPort.markAllRead(user, TENANT_A, loadedAt, Instant.now())).isEqualTo(1);
        assertThat(inboxRepositoryPort.countUnread(user, TENANT_A)).isEqualTo(1);
    }

    private Notification deliverTo(UUID tenantId, UUID userId, String body) {
        Notification notification = notificationRepositoryPort.save(Notification.create(tenantId,
                NotificationType.NEW_PODCAST, "New podcast available", body, null, "PODCAST", "p", "{}"));
        notificationRepositoryPort.saveRecipients(List.of(UserNotification.create(notification.getId(), userId)));
        return notification;
    }

    private NotificationDevice registerDevice(UUID tenantId) {
        UUID user = UUID.randomUUID();
        return deviceRepositoryPort.save(NotificationDevice.register(user, tenantId, "install-" + user,
                DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[" + user + "]", "1.5.0", "iPhone"));
    }

    private void savePreferences(UUID userId, Boolean pushEnabled, Map<NotificationType, Boolean> byType) {
        NotificationPreferences preferences = preferenceRepositoryPort.load(userId, TENANT_A);
        preferences.update(pushEnabled, byType == null ? Map.of() : byType);
        preferenceRepositoryPort.save(preferences);
    }
}
