package com.skateboard.notification.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.adapter.in.messaging.events.DomainEventEnvelope;
import com.skateboard.notification.adapter.in.messaging.events.PodcastPublishedPayload;
import com.skateboard.notification.application.port.in.HandlePodcastPublishedUseCase;
import com.skateboard.notification.infrastructure.messaging.EventTopology;
import com.skateboard.notification.infrastructure.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * The queue's only entry point.
 *
 * <p>It validates the envelope, translates it into a use-case input and does
 * nothing else — no notification policy lives here, so a second event type
 * means a second listener method rather than a growing conditional.
 *
 * <p>Two failure modes are handled differently on purpose. A malformed or
 * unknown message can never succeed, so it is dead-lettered immediately rather
 * than retried four times first (an {@link AmqpRejectAndDontRequeueException}
 * short-circuits the retry policy). Anything else — a database blip, Expo
 * being unreachable — is rethrown so the configured backoff gets its attempts
 * before the message lands in the DLQ.
 */
@Component
public class PodcastPublishedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PodcastPublishedEventListener.class);

    private static final String EVENT_TYPE = "PODCAST_PUBLISHED";
    private static final int SUPPORTED_VERSION = 1;

    private final HandlePodcastPublishedUseCase handlePodcastPublishedUseCase;
    private final ObjectMapper objectMapper;

    public PodcastPublishedEventListener(HandlePodcastPublishedUseCase handlePodcastPublishedUseCase,
                                          ObjectMapper objectMapper) {
        this.handlePodcastPublishedUseCase = handlePodcastPublishedUseCase;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = EventTopology.QUEUE)
    public void onMessage(DomainEventEnvelope<Map<String, Object>> envelope,
                          @Header(name = "X-Correlation-Id", required = false) String correlationId,
                          @Header(name = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey) {
        // Carrying the producer's correlation id across the hop is what makes
        // "why did this user get a push?" answerable from one log query.
        MDC.put(CorrelationIdFilter.MDC_KEY,
                correlationId == null || correlationId.isBlank()
                        ? UUID.randomUUID().toString()
                        : correlationId);
        try {
            handle(envelope, routingKey);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handle(DomainEventEnvelope<Map<String, Object>> envelope, String routingKey) {
        reject(envelope == null, "message body was not a domain event envelope");
        reject(envelope.eventId() == null, "envelope carried no eventId");
        reject(!EVENT_TYPE.equals(envelope.eventType()),
                "unsupported eventType '" + envelope.eventType() + "' on routing key " + routingKey);
        reject(envelope.version() == null || envelope.version() != SUPPORTED_VERSION,
                "unsupported payload version " + envelope.version());
        reject(envelope.tenantId() == null, "envelope carried no tenantId");
        reject(envelope.payload() == null, "envelope carried no payload");

        PodcastPublishedPayload payload = convert(envelope.payload());
        reject(payload.podcastId() == null || payload.podcastId().isBlank(), "payload carried no podcastId");

        handlePodcastPublishedUseCase.execute(new HandlePodcastPublishedUseCase.Input(
                envelope.eventId(),
                envelope.tenantId(),
                envelope.occurredAt(),
                payload.podcastId(),
                payload.slug(),
                payload.title(),
                payload.imageUrl()));
    }

    private PodcastPublishedPayload convert(Map<String, Object> payload) {
        try {
            return objectMapper.convertValue(payload, PodcastPublishedPayload.class);
        } catch (IllegalArgumentException e) {
            throw dropped("payload did not match PodcastPublishedPayload: " + e.getMessage());
        }
    }

    private void reject(boolean condition, String reason) {
        if (condition) {
            throw dropped(reason);
        }
    }

    private org.springframework.amqp.AmqpRejectAndDontRequeueException dropped(String reason) {
        log.error("Dead-lettering unprocessable event: {}", reason);
        return new org.springframework.amqp.AmqpRejectAndDontRequeueException(reason);
    }
}
