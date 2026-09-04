package com.skateboard.notification.infrastructure.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches {@code notification.events.dlq} (spec §26).
 *
 * <p>Before this, a message that exhausted its retries landed in the
 * dead-letter queue and nothing ever looked — the platform has no metrics
 * stack, so "did anything fail last night?" had no answer at all.
 *
 * <p>It watches rather than consumes on purpose: the point of a dead-letter
 * queue is that a human can inspect a failed message and shovel it back.
 * Draining it into a log would lose the body and the ability to replay. So this
 * only reports depth — as the {@code notification_events_dead_lettered} gauge
 * for whatever eventually scrapes {@code /actuator/prometheus}, and as a
 * scheduled WARN line so there is a breadcrumb in the meantime.
 */
@Component
@ConditionalOnProperty(prefix = "messaging.dead-letter", name = "monitor-enabled",
        havingValue = "true", matchIfMissing = true)
public class DeadLetterQueueMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueMonitor.class);

    private final AmqpAdmin amqpAdmin;

    public DeadLetterQueueMonitor(AmqpAdmin amqpAdmin, MeterRegistry meterRegistry) {
        this.amqpAdmin = amqpAdmin;
        Gauge.builder("notification.events.dead_lettered", this, DeadLetterQueueMonitor::currentDepth)
                .description("Messages sitting in " + EventTopology.DEAD_LETTER_QUEUE
                        + " — each is an event that could not be processed")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${messaging.dead-letter.poll-cron}")
    public void report() {
        long depth = currentDepth();
        if (depth > 0L) {
            log.warn("{} messages in {} — unprocessable events are waiting to be inspected or replayed",
                    depth, EventTopology.DEAD_LETTER_QUEUE);
        }
    }

    /**
     * Reads the broker's own count. Returns 0 when the broker is unreachable or
     * has not declared the queue yet — a monitor that throws on a transient
     * outage is worse than one that briefly reads low.
     */
    private long currentDepth() {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(EventTopology.DEAD_LETTER_QUEUE);
            return info == null ? 0L : info.getMessageCount();
        } catch (RuntimeException e) {
            log.debug("Could not read {} depth", EventTopology.DEAD_LETTER_QUEUE, e);
            return 0L;
        }
    }
}
