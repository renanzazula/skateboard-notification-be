package com.skateboard.notification.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.DispatchNotificationUseCase;
import com.skateboard.notification.application.port.in.HandlePodcastPublishedUseCase;
import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
import com.skateboard.notification.application.port.out.ProcessedEventPort;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HandlePodcastPublishedServiceTest {

    private static final UUID EVENT = UUID.fromString("4470ac44-224e-4115-a41e-bc554e91bf3d");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private ProcessedEventPort processedEventPort;
    @Mock private NotificationRepositoryPort notificationRepositoryPort;
    @Mock private DispatchNotificationUseCase dispatchNotificationUseCase;

    private HandlePodcastPublishedService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new HandlePodcastPublishedService(processedEventPort, new NotificationTemplateResolver(),
                notificationRepositoryPort, dispatchNotificationUseCase, new ObjectMapper());
        when(notificationRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dispatchNotificationUseCase.execute(any()))
                .thenReturn(new DispatchNotificationUseCase.Result(2, 2, 0, 0));
    }

    @Test
    void turnsAPublishedPodcastIntoANotificationCarryingTheEpisodeTitle() {
        when(processedEventPort.claim(EVENT, "PODCAST_PUBLISHED")).thenReturn(true);

        HandlePodcastPublishedUseCase.Result result = service.execute(input());

        assertThat(result.processed()).isTrue();
        assertThat(result.sent()).isEqualTo(2);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepositoryPort).save(captor.capture());
        Notification notification = captor.getValue();
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
        when(processedEventPort.claim(EVENT, "PODCAST_PUBLISHED")).thenReturn(true);

        service.execute(input());

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepositoryPort).save(captor.capture());
        assertThat(captor.getValue().getDataJson())
                .contains("\"targetType\":\"PODCAST\"")
                .contains("\"targetId\":\"123\"")
                .contains("\"targetSlug\":\"barcelona-street-sessions-14\"");
    }

    /**
     * A broker can redeliver and the producer re-emits with a stable event id;
     * neither may reach a handset twice.
     */
    @Test
    void aRedeliveredEventWritesNothingAndSendsNothing() {
        when(processedEventPort.claim(EVENT, "PODCAST_PUBLISHED")).thenReturn(false);

        HandlePodcastPublishedUseCase.Result result = service.execute(input());

        assertThat(result.processed()).isFalse();
        verifyNoInteractions(notificationRepositoryPort);
        verifyNoInteractions(dispatchNotificationUseCase);
    }

    @Test
    void claimsTheEventBeforeWritingAnything() {
        when(processedEventPort.claim(any(), anyString())).thenReturn(true);

        service.execute(input());

        var inOrder = org.mockito.Mockito.inOrder(processedEventPort, notificationRepositoryPort);
        inOrder.verify(processedEventPort).claim(EVENT, "PODCAST_PUBLISHED");
        inOrder.verify(notificationRepositoryPort).save(any());
    }

    private HandlePodcastPublishedUseCase.Input input() {
        return new HandlePodcastPublishedUseCase.Input(EVENT, TENANT, Instant.parse("2026-09-03T16:30:00Z"),
                "123", "barcelona-street-sessions-14", "Barcelona Street Sessions #14",
                "https://example.test/cover.jpg");
    }
}
