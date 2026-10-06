package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.EmailDeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.ProcessedEventPort;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.EmailProvider;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuestApplicationNotificationRecorderTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.randomUUID();
    private static final String EVENT_TYPE = "GUEST_APPLICATION_SUBMITTED";

    @Mock private ProcessedEventPort processedEventPort;
    @Mock private NotificationRecorder notificationRecorder;
    @Mock private EmailDeliveryRepositoryPort emailDeliveryRepositoryPort;

    private GuestApplicationNotificationRecorder recorder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        recorder = new GuestApplicationNotificationRecorder(processedEventPort, notificationRecorder,
                emailDeliveryRepositoryPort);
    }

    @Test
    void aRedeliveredEventWritesNothing() {
        when(processedEventPort.claim(EVENT, EVENT_TYPE)).thenReturn(false);

        assertThat(recorder.recordSubmission(EVENT, EVENT_TYPE, draft(), List.of(), List.of(ADMIN), List.of(email())))
                .isEmpty();

        verifyNoInteractions(notificationRecorder);
        verifyNoInteractions(emailDeliveryRepositoryPort);
    }

    @Test
    void claimsThenRecordsThePushNotificationThroughNotificationRecorder() {
        when(processedEventPort.claim(EVENT, EVENT_TYPE)).thenReturn(true);
        PreparedDispatch prepared = new PreparedDispatch(draft(), List.of(), List.of());
        when(notificationRecorder.recordForRecipients(any(), any(), any())).thenReturn(prepared);
        when(emailDeliveryRepositoryPort.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<GuestApplicationNotificationRecorder.Recorded> recorded =
                recorder.recordSubmission(EVENT, EVENT_TYPE, draft(), List.of(), List.of(ADMIN), List.of(email()));

        assertThat(recorded).isPresent();
        assertThat(recorded.get().pushDispatch()).contains(prepared);
        verify(notificationRecorder).recordForRecipients(any(), any(), any());
    }

    @Test
    void skipsThePushRecordingWhenNoAdminDraftIsGiven() {
        when(processedEventPort.claim(EVENT, EVENT_TYPE)).thenReturn(true);
        when(emailDeliveryRepositoryPort.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<GuestApplicationNotificationRecorder.Recorded> recorded =
                recorder.recordSubmission(EVENT, EVENT_TYPE, null, List.of(), List.of(), List.of(email()));

        assertThat(recorded).isPresent();
        assertThat(recorded.get().pushDispatch()).isEmpty();
        verifyNoInteractions(notificationRecorder);
    }

    @Test
    void savesEmailDeliveriesWhenAnyAreGiven() {
        when(processedEventPort.claim(EVENT, EVENT_TYPE)).thenReturn(true);
        EmailDelivery saved = email();
        when(emailDeliveryRepositoryPort.saveAll(List.of(saved))).thenReturn(List.of(saved));

        Optional<GuestApplicationNotificationRecorder.Recorded> recorded =
                recorder.recordSubmission(EVENT, EVENT_TYPE, null, List.of(), List.of(), List.of(saved));

        assertThat(recorded.get().emailDeliveries()).containsExactly(saved);
    }

    @Test
    void doesNotCallSaveAllWithAnEmptyEmailList() {
        when(processedEventPort.claim(EVENT, EVENT_TYPE)).thenReturn(true);

        recorder.recordSubmission(EVENT, EVENT_TYPE, null, List.of(), List.of(), List.of());

        verify(emailDeliveryRepositoryPort, never()).saveAll(any());
    }

    private Notification draft() {
        return Notification.create(TENANT, NotificationType.GUEST_APPLICATION_RECEIVED, "New guest application",
                "Jane Doe applied to be a podcast guest", null, "GUEST_APPLICATION", "app-1", "{}");
    }

    private EmailDelivery email() {
        return EmailDelivery.pending("GUEST_APPLICATION", "app-1", "jane@example.com",
                "We've received your podcast guest application", "Thanks!", EmailProvider.BREVO);
    }
}
