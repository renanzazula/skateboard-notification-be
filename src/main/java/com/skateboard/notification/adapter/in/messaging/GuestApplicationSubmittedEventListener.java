package com.skateboard.notification.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.adapter.in.messaging.events.DomainEventEnvelope;
import com.skateboard.notification.adapter.in.messaging.events.GuestApplicationSubmittedPayload;
import com.skateboard.notification.application.port.in.HandleGuestApplicationSubmittedUseCase;
import com.skateboard.notification.infrastructure.messaging.EventTopology;
import com.skateboard.notification.infrastructure.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Guest Application queue's only entry point — same shape and the same
 * two failure modes as {@link PodcastPublishedEventListener}; see its
 * javadoc. A separate class on a separate queue (EventTopology.GUEST_APPLICATION_QUEUE)
 * rather than a second {@code eventType} branch in that listener, so this
 * feature cannot regress podcast notification delivery, and vice versa.
 */
@Component
public class GuestApplicationSubmittedEventListener {

    private static final Logger log = LoggerFactory.getLogger(GuestApplicationSubmittedEventListener.class);

    private static final String EVENT_TYPE = "GUEST_APPLICATION_SUBMITTED";
    private static final int SUPPORTED_VERSION = 1;

    private final HandleGuestApplicationSubmittedUseCase handleGuestApplicationSubmittedUseCase;
    private final ObjectMapper objectMapper;

    public GuestApplicationSubmittedEventListener(
            HandleGuestApplicationSubmittedUseCase handleGuestApplicationSubmittedUseCase,
            ObjectMapper objectMapper) {
        this.handleGuestApplicationSubmittedUseCase = handleGuestApplicationSubmittedUseCase;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = EventTopology.GUEST_APPLICATION_QUEUE)
    public void onMessage(DomainEventEnvelope<Map<String, Object>> envelope,
                          @Header(name = "X-Correlation-Id", required = false) String correlationId,
                          @Header(name = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey) {
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

        GuestApplicationSubmittedPayload payload = convert(envelope.payload());
        reject(payload.applicationId() == null || payload.applicationId().isBlank(),
                "payload carried no applicationId");
        reject(payload.userId() == null || payload.userId().isBlank(), "payload carried no userId");

        handleGuestApplicationSubmittedUseCase.execute(new HandleGuestApplicationSubmittedUseCase.Input(
                envelope.eventId(),
                envelope.tenantId(),
                envelope.occurredAt(),
                payload.applicationId(),
                payload.userId(),
                payload.name(),
                payload.email(),
                payload.message(),
                payload.socialLinks() == null ? List.of() : payload.socialLinks()));
    }

    private GuestApplicationSubmittedPayload convert(Map<String, Object> payload) {
        try {
            return objectMapper.convertValue(payload, GuestApplicationSubmittedPayload.class);
        } catch (IllegalArgumentException e) {
            throw dropped("payload did not match GuestApplicationSubmittedPayload: " + e.getMessage());
        }
    }

    private void reject(boolean condition, String reason) {
        if (condition) {
            throw dropped(reason);
        }
    }

    private AmqpRejectAndDontRequeueException dropped(String reason) {
        log.error("Dead-lettering unprocessable event: {}", reason);
        return new AmqpRejectAndDontRequeueException(reason);
    }
}
