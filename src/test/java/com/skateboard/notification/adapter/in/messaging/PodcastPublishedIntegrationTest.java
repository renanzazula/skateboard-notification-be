package com.skateboard.notification.adapter.in.messaging;

import com.skateboard.notification.adapter.out.persistence.SpringNotificationDeliveryRepository;
import com.skateboard.notification.adapter.out.persistence.SpringNotificationRepository;
import com.skateboard.notification.adapter.out.persistence.SpringUserNotificationRepository;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.PushMessage;
import com.skateboard.notification.application.port.out.PushNotificationProviderPort;
import com.skateboard.notification.application.port.out.PushResult;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.PushProvider;
import com.skateboard.notification.infrastructure.messaging.EventTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The path the spec's initial use case describes, end to end inside this
 * service: an event lands on the queue and a notification, a recipient and a
 * delivery come out the other side.
 *
 * <p>Push goes through a fake provider rather than Expo (spec §35) — the point
 * is the pipeline, and a test that sends real notifications is a test nobody
 * can run twice.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "app.security.oauth2.audience=skateboard-notification-be",
        // The retry pass is scheduled, so left on it would fire mid-test and
        // re-send deliveries these assertions are counting.
        "push.retry.enabled=false"
})
@Testcontainers
class PodcastPublishedIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management-alpine");

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Records what it was asked to send and always accepts, so assertions can
     * be about the pipeline rather than about a provider's mood.
     */
    static class FakePushNotificationProvider implements PushNotificationProviderPort {

        final List<PushMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public PushProvider provider() {
            return PushProvider.EXPO;
        }

        @Override
        public List<PushResult> send(List<PushMessage> messages) {
            sent.addAll(messages);
            List<PushResult> results = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                results.add(PushResult.accepted("fake-ticket-" + i));
            }
            return results;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakePushNotificationProvider fakePushNotificationProvider() {
            return new FakePushNotificationProvider();
        }
    }

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private DeviceRepositoryPort deviceRepositoryPort;
    @Autowired private FakePushNotificationProvider pushProvider;
    @Autowired private SpringNotificationRepository notificationRepository;
    @Autowired private SpringUserNotificationRepository userNotificationRepository;
    @Autowired private SpringNotificationDeliveryRepository deliveryRepository;

    @BeforeEach
    void setUp() {
        pushProvider.sent.clear();
        notificationRepository.deleteAll();
        deliveryRepository.deleteAll();
        userNotificationRepository.deleteAll();
    }

    @Test
    void aPublishedPodcastBecomesANotificationARecipientAndADelivery() {
        registerDevice();
        UUID eventId = UUID.randomUUID();

        publish(eventId, "123", "barcelona-street-sessions-14", "Barcelona Street Sessions #14");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(notificationRepository.findAll()).hasSize(1);
            assertThat(userNotificationRepository.findAll()).hasSize(1);
            assertThat(deliveryRepository.findAll()).hasSize(1);
        });

        assertThat(notificationRepository.findAll()).singleElement().satisfies(notification -> {
            assertThat(notification.getTitle()).isEqualTo("New podcast available");
            assertThat(notification.getBody()).isEqualTo("Barcelona Street Sessions #14");
            assertThat(notification.getTenantId()).isEqualTo(TENANT);
        });
        assertThat(deliveryRepository.findAll()).singleElement()
                .satisfies(delivery -> assertThat(delivery.getStatus()).isEqualTo("SENT"));
        assertThat(pushProvider.sent).singleElement()
                .satisfies(message -> assertThat(message.data())
                        .containsEntry("targetSlug", "barcelona-street-sessions-14"));
    }

    /**
     * The producer re-emits with a stable event id and the broker may
     * redeliver; the user must be told once.
     */
    @Test
    void thesameEventDeliveredTwiceProducesOneNotificationAndOnePush() {
        registerDevice();
        UUID eventId = UUID.randomUUID();

        publish(eventId, "123", "slug", "Episode");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(notificationRepository.findAll()).hasSize(1));

        publish(eventId, "123", "slug", "Episode");

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(notificationRepository.findAll()).hasSize(1);
            assertThat(pushProvider.sent).hasSize(1);
        });
    }

    /**
     * A message that can never be processed must be observable rather than
     * silently gone (spec §26).
     */
    @Test
    void anUnprocessableEventEndsUpOnTheDeadLetterQueue() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "SOMETHING_ELSE");
        envelope.put("version", 1);
        envelope.put("tenantId", TENANT.toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", Map.of());

        rabbitTemplate.convertAndSend(EventTopology.EXCHANGE,
                EventTopology.PODCAST_PUBLISHED_ROUTING_KEY, envelope);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var info = amqpAdmin.getQueueInfo(EventTopology.DEAD_LETTER_QUEUE);
            assertThat(info).isNotNull();
            assertThat(info.getMessageCount()).isEqualTo(1);
        });
        assertThat(notificationRepository.findAll()).isEmpty();
    }

    private void registerDevice() {
        UUID user = UUID.randomUUID();
        deviceRepositoryPort.save(NotificationDevice.register(user, TENANT, "install-" + user,
                DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[" + user + "]", "1.5.0", "iPhone"));
    }

    private void publish(UUID eventId, String podcastId, String slug, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("podcastId", podcastId);
        payload.put("slug", slug);
        payload.put("title", title);
        payload.put("imageUrl", "https://example.test/cover.jpg");
        payload.put("publishedAt", Instant.now().toString());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "PODCAST_PUBLISHED");
        envelope.put("version", 1);
        envelope.put("tenantId", TENANT.toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);

        rabbitTemplate.convertAndSend(EventTopology.EXCHANGE,
                EventTopology.PODCAST_PUBLISHED_ROUTING_KEY, envelope);
    }
}
