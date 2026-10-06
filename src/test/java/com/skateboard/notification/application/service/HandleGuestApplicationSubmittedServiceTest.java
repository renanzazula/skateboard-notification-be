package com.skateboard.notification.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.HandleGuestApplicationSubmittedUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.GuestApplicationSettingsPort;
import com.skateboard.notification.application.port.out.RecipientDirectoryPort;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.domain.model.PushProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HandleGuestApplicationSubmittedServiceTest {

    private static final UUID EVENT = UUID.fromString("4470ac44-224e-4115-a41e-bc554e91bf3d");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private GuestApplicationSettingsPort settingsPort;
    @Mock private RecipientDirectoryPort recipientDirectoryPort;
    @Mock private DeviceRepositoryPort deviceRepositoryPort;
    @Mock private GuestApplicationNotificationRecorder recorder;
    @Mock private DispatchNotificationService dispatchNotificationService;
    @Mock private DispatchEmailService dispatchEmailService;

    private HandleGuestApplicationSubmittedService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new HandleGuestApplicationSubmittedService(settingsPort, recipientDirectoryPort,
                deviceRepositoryPort, new NotificationTemplateResolver(), recorder,
                dispatchNotificationService, dispatchEmailService, new ObjectMapper());
        when(recipientDirectoryPort.resolve(any())).thenReturn(List.of());
    }

    @Test
    void aRedeliveredEventSendsNothing() {
        when(settingsPort.getSettings()).thenReturn(Optional.empty());
        when(recorder.record(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        HandleGuestApplicationSubmittedUseCase.Result result = service.execute(input());

        assertThat(result.processed()).isFalse();
        verifyNoInteractions(dispatchNotificationService, dispatchEmailService);
    }

    @Test
    void notifiesEveryConfiguredRecipientInAppAndQueuesTheirEmailsWhenResolved() {
        when(settingsPort.getSettings()).thenReturn(Optional.of(new GuestApplicationSettingsPort.Settings(
                true, List.of(ADMIN_A, ADMIN_B), "We've received your podcast guest application", "Thanks {name}!")));
        when(recipientDirectoryPort.resolve(List.of(ADMIN_A, ADMIN_B))).thenReturn(List.of(
                new RecipientDirectoryPort.ResolvedRecipient(ADMIN_A, "admin-a@example.com", true, true),
                // Not emailable: excluded from email, still an in-app recipient.
                new RecipientDirectoryPort.ResolvedRecipient(ADMIN_B, "admin-b@example.com", false, true)));
        NotificationDevice device = device(ADMIN_A);
        when(deviceRepositoryPort.findEnabledDevicesOfUser(TENANT, ADMIN_A)).thenReturn(List.of(device));
        when(deviceRepositoryPort.findEnabledDevicesOfUser(TENANT, ADMIN_B)).thenReturn(List.of());
        recorderAccepts();

        HandleGuestApplicationSubmittedUseCase.Result result = service.execute(input());

        assertThat(result.processed()).isTrue();
        assertThat(result.adminsNotifiedInApp()).isEqualTo(2);
        // Applicant confirmation + one emailable admin (admin-b excluded).
        assertThat(result.emailsQueued()).isEqualTo(2);

        ArgumentCaptor<List<UUID>> recipientsCaptor = captureRecipientIds();
        assertThat(recipientsCaptor.getValue()).containsExactlyInAnyOrder(ADMIN_A, ADMIN_B);
        ArgumentCaptor<List<NotificationDevice>> devicesCaptor = captureDevices();
        assertThat(devicesCaptor.getValue()).containsExactly(device);

        verify(dispatchNotificationService).send(any());
        verify(dispatchEmailService).send(any());
    }

    @Test
    void theAdminNotificationCarriesTheApplicantNameAndReferencesTheApplication() {
        when(settingsPort.getSettings()).thenReturn(Optional.of(new GuestApplicationSettingsPort.Settings(
                true, List.of(ADMIN_A), null, null)));
        when(deviceRepositoryPort.findEnabledDevicesOfUser(eq(TENANT), any())).thenReturn(List.of());
        recorderAccepts();

        service.execute(input());

        Notification draft = capturedAdminDraft();
        assertThat(draft.getType()).isEqualTo(NotificationType.GUEST_APPLICATION_RECEIVED);
        assertThat(draft.getBody()).isEqualTo("Jane Doe applied to be a podcast guest");
        assertThat(draft.getReferenceType()).isEqualTo("GUEST_APPLICATION");
        assertThat(draft.getReferenceId()).isEqualTo("app-123");
        assertThat(draft.getDataJson()).contains("\"targetId\":\"app-123\"");
    }

    @Test
    void skipsTheAdminNotificationWhenNoRecipientsAreConfigured() {
        when(settingsPort.getSettings()).thenReturn(Optional.of(new GuestApplicationSettingsPort.Settings(
                true, List.of(), "subject", "body")));
        recorderAccepts();

        HandleGuestApplicationSubmittedUseCase.Result result = service.execute(input());

        assertThat(result.adminsNotifiedInApp()).isZero();
        verifyNoInteractions(deviceRepositoryPort);
        ArgumentCaptor<Notification> draftCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(recorder).record(any(), any(), draftCaptor.capture(), any(), any(), any());
        assertThat(draftCaptor.getValue()).isNull();
    }

    /** Settings outage must not cost the applicant their confirmation — default copy still goes out. */
    @Test
    void fallsBackToDefaultConfirmationCopyWhenSettingsCannotBeRead() {
        when(settingsPort.getSettings()).thenReturn(Optional.empty());
        recorderAccepts();

        service.execute(input());

        EmailDelivery confirmation = capturedEmails().get(0);
        assertThat(confirmation.getRecipientEmail()).isEqualTo("jane@example.com");
        assertThat(confirmation.getSubject()).isEqualTo("We've received your podcast guest application");
        assertThat(confirmation.getBody()).contains("Thank you for your interest");
    }

    @Test
    void substitutesTheNamePlaceholderInTheConfiguredConfirmationBody() {
        when(settingsPort.getSettings()).thenReturn(Optional.of(new GuestApplicationSettingsPort.Settings(
                true, List.of(), "Custom subject", "Hi {name}, thanks for applying!")));
        recorderAccepts();

        service.execute(input());

        EmailDelivery confirmation = capturedEmails().get(0);
        assertThat(confirmation.getSubject()).isEqualTo("Custom subject");
        assertThat(confirmation.getBody()).isEqualTo("Hi Jane Doe, thanks for applying!");
    }

    @Test
    void queuesNoConfirmationWhenTheApplicantLeftNoEmail() {
        when(settingsPort.getSettings()).thenReturn(Optional.empty());
        recorderAccepts();

        service.execute(new HandleGuestApplicationSubmittedUseCase.Input(EVENT, TENANT,
                Instant.parse("2026-10-05T16:30:00Z"), "app-123", "user-456", "Jane Doe", "",
                "I love skating", List.of()));

        assertThat(capturedEmails()).isEmpty();
    }

    @Test
    void recordsEverythingBeforeDispatchingAnything() {
        when(settingsPort.getSettings()).thenReturn(Optional.empty());
        recorderAccepts();

        service.execute(input());

        var inOrder = org.mockito.Mockito.inOrder(recorder, dispatchEmailService);
        inOrder.verify(recorder).record(eq(EVENT), eq("GUEST_APPLICATION_SUBMITTED"), any(), any(), any(), any());
        inOrder.verify(dispatchEmailService).send(any());
    }

    private void recorderAccepts() {
        when(recorder.record(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Notification draft = invocation.getArgument(2);
            List<NotificationDevice> devices = invocation.getArgument(3);
            List<EmailDelivery> emails = invocation.getArgument(5);
            List<com.skateboard.notification.domain.model.NotificationDelivery> deliveries = devices.stream()
                    .map(d -> com.skateboard.notification.domain.model.NotificationDelivery.pending(
                            draft == null ? null : draft.getId(), d.getUserId(), d.getId(), d.getPushProvider()))
                    .toList();
            Optional<PreparedDispatch> dispatch = draft == null
                    ? Optional.empty()
                    : Optional.of(new PreparedDispatch(draft, devices, deliveries));
            return Optional.of(new GuestApplicationNotificationRecorder.Recorded(dispatch, emails));
        });
    }

    private Notification capturedAdminDraft() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(recorder).record(any(), any(), captor.capture(), any(), any(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<EmailDelivery> capturedEmails() {
        ArgumentCaptor<List<EmailDelivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(recorder).record(any(), any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<UUID>> captureRecipientIds() {
        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(recorder).record(any(), any(), any(), any(), captor.capture(), any());
        return captor;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<NotificationDevice>> captureDevices() {
        ArgumentCaptor<List<NotificationDevice>> captor = ArgumentCaptor.forClass(List.class);
        verify(recorder).record(any(), any(), any(), captor.capture(), any(), any());
        return captor;
    }

    private NotificationDevice device(UUID userId) {
        return NotificationDevice.register(userId, TENANT, "device-1", DevicePlatform.IOS,
                PushProvider.EXPO, "ExponentPushToken[a]", "1.5.0", "device-1");
    }

    private HandleGuestApplicationSubmittedUseCase.Input input() {
        return new HandleGuestApplicationSubmittedUseCase.Input(EVENT, TENANT,
                Instant.parse("2026-10-05T16:30:00Z"), "app-123", "user-456", "Jane Doe", "jane@example.com",
                "I love skating", List.of("https://instagram.com/jane"));
    }
}
