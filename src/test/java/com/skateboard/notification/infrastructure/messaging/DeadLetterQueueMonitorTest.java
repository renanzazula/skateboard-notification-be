package com.skateboard.notification.infrastructure.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The dead-letter queue is the platform's only "something failed" signal, so
 * the gauge that reports its depth has to stay readable even when the broker is
 * having a bad time.
 */
class DeadLetterQueueMonitorTest {

    @Mock private AmqpAdmin amqpAdmin;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new SimpleMeterRegistry();
    }

    @Test
    void reportsTheBrokersOwnCountAsAGauge() {
        when(amqpAdmin.getQueueInfo(EventTopology.DEAD_LETTER_QUEUE))
                .thenReturn(new QueueInformation(EventTopology.DEAD_LETTER_QUEUE, 3, 0));
        new DeadLetterQueueMonitor(amqpAdmin, registry);

        assertThat(registry.get("notification.events.dead_lettered").gauge().value()).isEqualTo(3.0);
    }

    @Test
    void readsZeroRatherThanThrowingWhenTheQueueIsNotDeclaredYet() {
        when(amqpAdmin.getQueueInfo(EventTopology.DEAD_LETTER_QUEUE)).thenReturn(null);
        new DeadLetterQueueMonitor(amqpAdmin, registry);

        assertThat(registry.get("notification.events.dead_lettered").gauge().value()).isZero();
    }

    @Test
    void readsZeroRatherThanThrowingWhenTheBrokerIsUnreachable() {
        when(amqpAdmin.getQueueInfo(EventTopology.DEAD_LETTER_QUEUE))
                .thenThrow(new org.springframework.amqp.AmqpConnectException(new RuntimeException("down")));
        DeadLetterQueueMonitor monitor = new DeadLetterQueueMonitor(amqpAdmin, registry);

        assertThat(registry.get("notification.events.dead_lettered").gauge().value()).isZero();
        monitor.report(); // must not throw
    }
}
