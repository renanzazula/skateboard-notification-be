package com.skateboard.notification.infrastructure.metrics;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.NotificationMetricsPort;
import com.skateboard.notification.domain.model.NotificationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The only class that knows Micrometer exists.
 *
 * <p>Counter names are dotted here and land in Prometheus as
 * {@code notifications_sent_total}, {@code notifications_invalid_tokens_total}
 * and so on (spec §31). Every delivery counter carries a {@code type} tag so a
 * dashboard can split podcast pushes from the rest; the tag is a bounded enum,
 * so cardinality stays fixed.
 *
 * <p>{@code notification_deliveries_pending} is a gauge rather than a counter:
 * it answers "is the retry pass keeping up right now?", which is the first
 * question during a live incident. It reads a single indexed {@code count(*)}
 * on each scrape.
 */
@Component
public class MicrometerNotificationMetrics implements NotificationMetricsPort {

    private static final String SENT = "notifications.sent";
    private static final String RETRYABLE = "notifications.retryable";
    private static final String FAILED = "notifications.failed";
    private static final String INVALID_TOKENS = "notifications.invalid_tokens";
    private static final String EVENTS_PROCESSED = "notification.events.processed";
    private static final String EVENTS_DUPLICATE = "notification.events.duplicate";

    private final MeterRegistry registry;

    public MicrometerNotificationMetrics(MeterRegistry registry, DeliveryRepositoryPort deliveryRepositoryPort) {
        this.registry = registry;
        Gauge.builder("notification.deliveries.pending", deliveryRepositoryPort,
                        DeliveryRepositoryPort::countPendingDeliveries)
                .description("Deliveries still owed a send — the retry pass's backlog")
                .register(registry);
    }

    @Override
    public void eventProcessed(String eventType) {
        registry.counter(EVENTS_PROCESSED, "eventType", eventType).increment();
    }

    @Override
    public void eventIgnoredAsDuplicate(String eventType) {
        registry.counter(EVENTS_DUPLICATE, "eventType", eventType).increment();
    }

    @Override
    public void recordDeliveryOutcomes(NotificationType type, int accepted, int retryable, int failed,
                                       int invalidTokens) {
        bump(SENT, type, accepted);
        bump(RETRYABLE, type, retryable);
        bump(FAILED, type, failed);
        bump(INVALID_TOKENS, type, invalidTokens);
    }

    private void bump(String name, NotificationType type, int count) {
        if (count > 0) {
            Counter.builder(name).tag("type", type.name()).register(registry).increment(count);
        }
    }
}
