package com.skateboard.notification.infrastructure.metrics;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.domain.model.NotificationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The names and tags here are a contract with whatever ends up scraping
 * {@code /actuator/prometheus} — a dashboard or alert keys on them, so a rename
 * is a breaking change and worth a failing test.
 */
class MicrometerNotificationMetricsTest {

    @Mock private DeliveryRepositoryPort deliveryRepositoryPort;

    private SimpleMeterRegistry registry;
    private MicrometerNotificationMetrics metrics;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerNotificationMetrics(registry, deliveryRepositoryPort);
    }

    @Test
    void countsDeliveryOutcomesPerNotificationType() {
        metrics.recordDeliveryOutcomes(NotificationType.NEW_PODCAST, 3, 1, 0, 2);

        assertThat(counter("notifications.sent")).isEqualTo(3.0);
        assertThat(counter("notifications.retryable")).isEqualTo(1.0);
        assertThat(counter("notifications.invalid_tokens")).isEqualTo(2.0);
        assertThat(registry.find("notifications.failed").counter()).isNull();
    }

    @Test
    void keepsCountsSeparatePerType() {
        metrics.recordDeliveryOutcomes(NotificationType.NEW_PODCAST, 2, 0, 0, 0);
        metrics.recordDeliveryOutcomes(NotificationType.NEW_POST, 5, 0, 0, 0);

        assertThat(registry.get("notifications.sent").tag("type", "NEW_PODCAST").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("notifications.sent").tag("type", "NEW_POST").counter().count()).isEqualTo(5.0);
    }

    @Test
    void countsProcessedAndDuplicateEvents() {
        metrics.eventProcessed("PODCAST_PUBLISHED");
        metrics.eventProcessed("PODCAST_PUBLISHED");
        metrics.eventIgnoredAsDuplicate("PODCAST_PUBLISHED");

        assertThat(registry.get("notification.events.processed").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("notification.events.duplicate").counter().count()).isEqualTo(1.0);
    }

    @Test
    void exposesThePendingDeliveryBacklogAsAGauge() {
        when(deliveryRepositoryPort.countPendingDeliveries()).thenReturn(7L);

        assertThat(registry.get("notification.deliveries.pending").gauge().value()).isEqualTo(7.0);
    }

    private double counter(String name) {
        return registry.get(name).tag("type", "NEW_PODCAST").counter().count();
    }
}
