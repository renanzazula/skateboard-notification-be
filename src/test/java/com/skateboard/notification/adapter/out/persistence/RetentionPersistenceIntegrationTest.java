package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.service.PurgeExpiredDataService;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.PushProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retention pass against a real Postgres: the batched {@code DELETE ... IN
 * (SELECT ... LIMIT n)} statements and, above all, that removing an aged
 * {@code notification} takes its {@code user_notification} and
 * {@code notification_delivery} rows with it by {@code ON DELETE CASCADE} —
 * behaviour a mock cannot show.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "app.security.oauth2.audience=skateboard-notification-be",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // Background jobs are all default-on. Left running inside a test they
        // fire mid-assertion, mutate the rows under it, and — for the retry pass,
        // which has no fake provider here — would reach out to the real Expo API.
        "push.retry.enabled=false",
        "retention.enabled=false",
        "messaging.dead-letter.monitor-enabled=false"
})
@Testcontainers
class RetentionPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired private PurgeExpiredDataService purgeExpiredDataService;
    @Autowired private DeviceRepositoryPort deviceRepositoryPort;
    @Autowired private SpringNotificationRepository notificationRepository;
    @Autowired private SpringUserNotificationRepository userNotificationRepository;
    @Autowired private SpringNotificationDeliveryRepository deliveryRepository;
    @Autowired private SpringProcessedEventRepository processedEventRepository;

    @BeforeEach
    void clean() {
        deliveryRepository.deleteAll();
        userNotificationRepository.deleteAll();
        notificationRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    void deletesAnAgedNotificationAndCascadesToItsRecipientAndDeliveryRows() {
        UUID device = registerDevice();
        UUID aged = notification(Instant.now().minus(Duration.ofDays(100)));
        recipientAndDelivery(aged, device);
        UUID fresh = notification(Instant.now().minus(Duration.ofDays(1)));
        recipientAndDelivery(fresh, device);

        purgeExpiredDataService.run();

        assertThat(notificationRepository.findById(aged)).isEmpty();
        assertThat(notificationRepository.findById(fresh)).isPresent();
        assertThat(userNotificationRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.findAll().get(0).getNotificationId()).isEqualTo(fresh);
    }

    @Test
    void prunesTheIdempotencyLedgerOnceARedeliveryCanNoLongerArrive() {
        processedEvent(Instant.now().minus(Duration.ofDays(30)));
        UUID recent = processedEvent(Instant.now().minus(Duration.ofHours(1)));

        purgeExpiredDataService.run();

        assertThat(processedEventRepository.findAll())
                .singleElement()
                .satisfies(row -> assertThat(row.getEventId()).isEqualTo(recent));
    }

    private UUID registerDevice() {
        UUID user = UUID.randomUUID();
        return deviceRepositoryPort.save(NotificationDevice.register(user, TENANT, "install-" + user,
                DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[" + user + "]", "1.5.0", "iPhone")).getId();
    }

    private UUID notification(Instant createdAt) {
        NotificationJpaEntity entity = new NotificationJpaEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT);
        entity.setType("NEW_PODCAST");
        entity.setTitle("New podcast available");
        entity.setBody("Episode");
        entity.setData("{}");
        entity.setCreatedAt(createdAt);
        return notificationRepository.save(entity).getId();
    }

    private void recipientAndDelivery(UUID notificationId, UUID deviceId) {
        UUID user = UUID.randomUUID();

        UserNotificationJpaEntity recipient = new UserNotificationJpaEntity();
        recipient.setId(UUID.randomUUID());
        recipient.setNotificationId(notificationId);
        recipient.setUserId(user);
        recipient.setCreatedAt(Instant.now());
        userNotificationRepository.save(recipient);

        NotificationDeliveryJpaEntity delivery = new NotificationDeliveryJpaEntity();
        delivery.setId(UUID.randomUUID());
        delivery.setNotificationId(notificationId);
        delivery.setUserId(user);
        delivery.setDeviceId(deviceId);
        delivery.setChannel("PUSH");
        delivery.setProvider("EXPO");
        delivery.setStatus("SENT");
        delivery.setAttemptCount(1);
        delivery.setCreatedAt(Instant.now());
        delivery.setUpdatedAt(Instant.now());
        deliveryRepository.save(delivery);
    }

    private UUID processedEvent(Instant processedAt) {
        ProcessedEventJpaEntity entity = new ProcessedEventJpaEntity();
        entity.setEventId(UUID.randomUUID());
        entity.setEventType("PODCAST_PUBLISHED");
        entity.setProcessedAt(processedAt);
        return processedEventRepository.save(entity).getEventId();
    }
}
