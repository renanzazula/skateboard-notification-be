package com.skateboard.notification.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.HandlePodcastPublishedUseCase;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HandlePodcastPublishedServiceTest {

    private static final UUID EVENT = UUID.fromString("4470ac44-224e-4115-a41e-bc554e91bf3d");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private NotificationRecorder notificationRecorder;
    @Mock private DispatchNotificationService dispatchNotificationService;

    private HandlePodcastPublishedService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new HandlePodcastPublishedService(new NotificationTemplateResolver(),
                notificationRecorder, dispatchNotificationService, new ObjectMapper());
        when(dispatchNotificationService.send(any()))
                .thenReturn(new DispatchNotificationService.Result(2, 2, 0, 0, 0));
    }

    @Test
    void turnsAPublishedPodcastIntoANotificationCarryingTheEpisodeTitle() {
        recorderAccepts();

        HandlePodcastPublishedUseCase.Result result = service.execute(input());

        assertThat(result.processed()).isTrue();
        assertThat(result.sent()).isEqualTo(2);

        Notification notification = capturedDraft();
        assertThat(notification.getType()).isEqualTo(NotificationType.NEW_PODCAST);
        assertThat(notification.getTenantId()).isEqualTo(TENANT);
        assertThat(notification.getTitle()).isEqualTo("New podcast available");
        assertThat(notification.getBody()).isEqualTo("Barcelona Street Sessions #14");
        assertThat(notification.getReferenceType()).isEqualTo("PODCAST");
        assertThat(notification.getReferenceId()).isEqualTo("123");
    }

    /**
     * The app routes by slug, so the deep-link payload has to carry one — the
     * whole point of semantic targets over a backend-built URL.
     */
    @Test
    void storesSemanticNavigationTargetsIncludingTheSlug() {
        recorderAccepts();

        service.execute(input());

        assertThat(capturedDraft().getDataJson())
                .contains("\"targetType\":\"PODCAST\"")
                .contains("\"targetId\":\"123\"")
                .contains("\"targetSlug\":\"barcelona-street-sessions-14\"");
    }

    /**
     * A broker can redeliver and the producer re-emits with a stable event id;
     * neither may reach a handset twice.
     */
    @Test
    void aRedeliveredEventSendsNothing() {
        when(notificationRecorder.recordEvent(any(), anyString(), any())).thenReturn(Optional.empty());

        HandlePodcastPublishedUseCase.Result result = service.execute(input());

        assertThat(result.processed()).isFalse();
        verifyNoInteractions(dispatchNotificationService);
    }

    /**
     * Persisting and sending are separate phases so that everything which must
     * be consistent commits together, before anything leaves the process. The
     * handler must not reorder them.
     */
    @Test
    void recordsEverythingBeforeSendingAnything() {
        recorderAccepts();

        service.execute(input());

        var inOrder = org.mockito.Mockito.inOrder(notificationRecorder, dispatchNotificationService);
        inOrder.verify(notificationRecorder).recordEvent(eq(EVENT), eq("PODCAST_PUBLISHED"), any());
        inOrder.verify(dispatchNotificationService).send(any());
    }

    private void recorderAccepts() {
        when(notificationRecorder.recordEvent(any(), anyString(), any())).thenAnswer(invocation ->
                Optional.of(new PreparedDispatch(invocation.getArgument(2), java.util.List.of(),
                        java.util.List.of())));
    }

    private Notification capturedDraft() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRecorder).recordEvent(any(), anyString(), captor.capture());
        return captor.getValue();
    }

    private HandlePodcastPublishedUseCase.Input input() {
        return new HandlePodcastPublishedUseCase.Input(EVENT, TENANT, Instant.parse("2026-09-03T16:30:00Z"),
                "123", "barcelona-street-sessions-14", "Barcelona Street Sessions #14",
                "https://example.test/cover.jpg");
    }
}
