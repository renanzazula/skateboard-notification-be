package com.skateboard.notification.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skateboard.notification.adapter.in.messaging.events.DomainEventEnvelope;
import com.skateboard.notification.application.port.in.HandlePodcastPublishedUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Envelope validation. Every rejection here must be an
 * {@link AmqpRejectAndDontRequeueException}: these are messages that can never
 * succeed, and retrying them four times before dead-lettering only delays the
 * queue behind them.
 */
class PodcastPublishedEventListenerTest {

    private static final UUID EVENT = UUID.fromString("4470ac44-224e-4115-a41e-bc554e91bf3d");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private HandlePodcastPublishedUseCase handlePodcastPublishedUseCase;

    private PodcastPublishedEventListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new PodcastPublishedEventListener(handlePodcastPublishedUseCase, objectMapper());
        when(handlePodcastPublishedUseCase.execute(any()))
                .thenReturn(new HandlePodcastPublishedUseCase.Result(true, 1, 1));
    }

    @Test
    void passesAValidEventThroughToTheUseCase() {
        listener.onMessage(envelope(payload()), "corr-1", "podcast.published.v1");

        ArgumentCaptor<HandlePodcastPublishedUseCase.Input> captor =
                ArgumentCaptor.forClass(HandlePodcastPublishedUseCase.Input.class);
        verify(handlePodcastPublishedUseCase).execute(captor.capture());
        HandlePodcastPublishedUseCase.Input input = captor.getValue();
        assertThat(input.eventId()).isEqualTo(EVENT);
        assertThat(input.tenantId()).isEqualTo(TENANT);
        assertThat(input.podcastId()).isEqualTo("123");
        assertThat(input.slug()).isEqualTo("barcelona-street-sessions-14");
    }

    @Test
    void worksWithoutAnInboundCorrelationId() {
        listener.onMessage(envelope(payload()), null, "podcast.published.v1");

        verify(handlePodcastPublishedUseCase).execute(any());
    }

    @Test
    void deadLettersAnEnvelopeWithNoEventId() {
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                null, "PODCAST_PUBLISHED", 1, TENANT, Instant.now(), payload());

        assertRejected(envelope, "eventId");
    }

    @Test
    void deadLettersAnUnknownEventType() {
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "MAGAZINE_PUBLISHED", 1, TENANT, Instant.now(), payload());

        assertRejected(envelope, "unsupported eventType");
    }

    /**
     * A future payload version is not something this build can interpret;
     * guessing at it would produce a wrong notification rather than none.
     */
    @Test
    void deadLettersAPayloadVersionItCannotInterpret() {
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "PODCAST_PUBLISHED", 2, TENANT, Instant.now(), payload());

        assertRejected(envelope, "unsupported payload version");
    }

    @Test
    void deadLettersAnEventWithNoTenant() {
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "PODCAST_PUBLISHED", 1, null, Instant.now(), payload());

        assertRejected(envelope, "tenantId");
    }

    @Test
    void deadLettersAPayloadWithNoPodcastId() {
        Map<String, Object> payload = payload();
        payload.remove("podcastId");
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "PODCAST_PUBLISHED", 1, TENANT, Instant.now(), payload);

        assertRejected(envelope, "podcastId");
    }

    private void assertRejected(DomainEventEnvelope<Map<String, Object>> envelope, String reason) {
        assertThatThrownBy(() -> listener.onMessage(envelope, "corr-1", "podcast.published.v1"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining(reason);
        verifyNoInteractions(handlePodcastPublishedUseCase);
    }

    /**
     * Mirrors the mapper Spring Boot autoconfigures, which is what the
     * listener is given in production: without JavaTimeModule an ISO-8601
     * occurredAt fails to bind and every event would dead-letter.
     */
    private ObjectMapper objectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    private DomainEventEnvelope<Map<String, Object>> envelope(Map<String, Object> payload) {
        return new DomainEventEnvelope<>(EVENT, "PODCAST_PUBLISHED", 1, TENANT,
                Instant.parse("2026-09-03T16:30:00Z"), payload);
    }

    private Map<String, Object> payload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("podcastId", "123");
        payload.put("slug", "barcelona-street-sessions-14");
        payload.put("title", "Barcelona Street Sessions #14");
        payload.put("imageUrl", "https://example.test/cover.jpg");
        payload.put("publishedAt", "2026-09-03T16:30:00Z");
        return payload;
    }
}
