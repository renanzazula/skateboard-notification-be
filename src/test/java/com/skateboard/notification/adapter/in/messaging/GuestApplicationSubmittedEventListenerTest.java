package com.skateboard.notification.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skateboard.notification.adapter.in.messaging.events.DomainEventEnvelope;
import com.skateboard.notification.application.port.in.HandleGuestApplicationSubmittedUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Envelope validation, mirroring {@link PodcastPublishedEventListenerTest}. */
class GuestApplicationSubmittedEventListenerTest {

    private static final UUID EVENT = UUID.fromString("4470ac44-224e-4115-a41e-bc554e91bf3d");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private HandleGuestApplicationSubmittedUseCase handleGuestApplicationSubmittedUseCase;

    private GuestApplicationSubmittedEventListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new GuestApplicationSubmittedEventListener(handleGuestApplicationSubmittedUseCase, objectMapper());
        when(handleGuestApplicationSubmittedUseCase.execute(any()))
                .thenReturn(new HandleGuestApplicationSubmittedUseCase.Result(true, 1, 2));
    }

    @Test
    void passesAValidEventThroughToTheUseCase() {
        listener.onMessage(envelope(payload()), "corr-1", "podcast.guest-application.submitted.v1");

        ArgumentCaptor<HandleGuestApplicationSubmittedUseCase.Input> captor =
                ArgumentCaptor.forClass(HandleGuestApplicationSubmittedUseCase.Input.class);
        verify(handleGuestApplicationSubmittedUseCase).execute(captor.capture());
        HandleGuestApplicationSubmittedUseCase.Input input = captor.getValue();
        assertThat(input.eventId()).isEqualTo(EVENT);
        assertThat(input.tenantId()).isEqualTo(TENANT);
        assertThat(input.applicationId()).isEqualTo("app-123");
        assertThat(input.userId()).isEqualTo("user-456");
        assertThat(input.name()).isEqualTo("Jane Doe");
        assertThat(input.socialLinks()).containsExactly("https://instagram.com/jane");
    }

    @Test
    void defaultsSocialLinksToAnEmptyListWhenAbsent() {
        Map<String, Object> payload = payload();
        payload.remove("socialLinks");

        listener.onMessage(envelope(payload), "corr-1", "podcast.guest-application.submitted.v1");

        ArgumentCaptor<HandleGuestApplicationSubmittedUseCase.Input> captor =
                ArgumentCaptor.forClass(HandleGuestApplicationSubmittedUseCase.Input.class);
        verify(handleGuestApplicationSubmittedUseCase).execute(captor.capture());
        assertThat(captor.getValue().socialLinks()).isEmpty();
    }

    @Test
    void worksWithoutAnInboundCorrelationId() {
        listener.onMessage(envelope(payload()), null, "podcast.guest-application.submitted.v1");

        verify(handleGuestApplicationSubmittedUseCase).execute(any());
    }

    @Test
    void deadLettersAnEnvelopeWithNoEventId() {
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                null, "GUEST_APPLICATION_SUBMITTED", 1, TENANT, Instant.now(), payload());

        assertRejected(envelope, "eventId");
    }

    @Test
    void deadLettersAnUnknownEventType() {
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "PODCAST_PUBLISHED", 1, TENANT, Instant.now(), payload());

        assertRejected(envelope, "unsupported eventType");
    }

    @Test
    void deadLettersAPayloadVersionItCannotInterpret() {
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "GUEST_APPLICATION_SUBMITTED", 2, TENANT, Instant.now(), payload());

        assertRejected(envelope, "unsupported payload version");
    }

    @Test
    void deadLettersAnEventWithNoTenant() {
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "GUEST_APPLICATION_SUBMITTED", 1, null, Instant.now(), payload());

        assertRejected(envelope, "tenantId");
    }

    @Test
    void deadLettersAPayloadWithNoApplicationId() {
        Map<String, Object> payload = payload();
        payload.remove("applicationId");
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "GUEST_APPLICATION_SUBMITTED", 1, TENANT, Instant.now(), payload);

        assertRejected(envelope, "applicationId");
    }

    @Test
    void deadLettersAPayloadWithNoUserId() {
        Map<String, Object> payload = payload();
        payload.remove("userId");
        DomainEventEnvelope<Map<String, Object>> envelope = new DomainEventEnvelope<>(
                EVENT, "GUEST_APPLICATION_SUBMITTED", 1, TENANT, Instant.now(), payload);

        assertRejected(envelope, "userId");
    }

    private void assertRejected(DomainEventEnvelope<Map<String, Object>> envelope, String reason) {
        assertThatThrownBy(() -> listener.onMessage(envelope, "corr-1", "podcast.guest-application.submitted.v1"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining(reason);
        verifyNoInteractions(handleGuestApplicationSubmittedUseCase);
    }

    private ObjectMapper objectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }

    private DomainEventEnvelope<Map<String, Object>> envelope(Map<String, Object> payload) {
        return new DomainEventEnvelope<>(EVENT, "GUEST_APPLICATION_SUBMITTED", 1, TENANT,
                Instant.parse("2026-10-05T16:30:00Z"), payload);
    }

    private Map<String, Object> payload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("applicationId", "app-123");
        payload.put("userId", "user-456");
        payload.put("name", "Jane Doe");
        payload.put("email", "jane@example.com");
        payload.put("message", "I love skating");
        payload.put("socialLinks", List.of("https://instagram.com/jane"));
        payload.put("submittedAt", "2026-10-05T16:30:00Z");
        return payload;
    }
}
