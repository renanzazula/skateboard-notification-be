package com.skateboard.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the whole topology. This service owns it because it owns the queue;
 * producers declare only the exchange, which keeps them from needing to know
 * who is listening (spec §7).
 *
 * <p>Retry and backoff are configured in application.yml rather than here.
 * The one piece that must be in code is the dead-letter wiring: a queue
 * without it either loses a poison message or redelivers it forever, and
 * neither is an acceptable answer to "which events failed?" (spec §26).
 */
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange applicationEventsExchange() {
        return new TopicExchange(EventTopology.EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange applicationEventsDeadLetterExchange() {
        return new TopicExchange(EventTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationEventsQueue() {
        return QueueBuilder.durable(EventTopology.QUEUE)
                .deadLetterExchange(EventTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(EventTopology.DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue notificationEventsDeadLetterQueue() {
        return QueueBuilder.durable(EventTopology.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding podcastPublishedBinding(Queue notificationEventsQueue,
                                            TopicExchange applicationEventsExchange) {
        return BindingBuilder.bind(notificationEventsQueue)
                .to(applicationEventsExchange)
                .with(EventTopology.PODCAST_PUBLISHED_BINDING);
    }

    @Bean
    public Binding deadLetterBinding(Queue notificationEventsDeadLetterQueue,
                                      TopicExchange applicationEventsDeadLetterExchange) {
        return BindingBuilder.bind(notificationEventsDeadLetterQueue)
                .to(applicationEventsDeadLetterExchange)
                .with(EventTopology.DEAD_LETTER_QUEUE);
    }

    /**
     * Uses the application's own ObjectMapper so the JavaTimeModule Boot
     * configured is in play — without it an ISO-8601 occurredAt fails to
     * deserialize and every event dead-letters.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
