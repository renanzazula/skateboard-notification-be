package com.skateboard.notification.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.PushMessage;
import com.skateboard.notification.application.port.out.PushNotificationProviderPort;
import com.skateboard.notification.application.port.out.PushResult;
import com.skateboard.notification.domain.model.DeliveryStatus;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.domain.model.PushProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What each provider outcome does to the delivery row and the device behind it.
 * A wrong answer here is expensive and quiet: treating a dead token as
 * retryable burns an attempt on every future notification, and treating a
 * timeout as permanent drops a notification with nothing to show for it.
 */
class DispatchNotificationServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private DeviceRepositoryPort deviceRepositoryPort;
    @Mock private DeliveryRepositoryPort deliveryRepositoryPort;
    @Mock private PushNotificationProviderPort pushNotificationProviderPort;

    private DispatchNotificationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DispatchNotificationService(deviceRepositoryPort, deliveryRepositoryPort,
                pushNotificationProviderPort, new ObjectMapper());
        when(deliveryRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void sendsNothingAndTouchesNoProviderWhenThereIsNothingPrepared() {
        DispatchNotificationService.Result result =
                service.send(new PreparedDispatch(notification(), List.of(), List.of()));

        assertThat(result.devicesTargeted()).isZero();
        verifyNoInteractions(pushNotificationProviderPort);
    }

    @Test
    void anAcceptedTicketMarksTheDeliverySent() {
        PreparedDispatch prepared = prepared(USER_A);
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(PushResult.accepted("ticket-1")));

        assertThat(service.send(prepared).sent()).isEqualTo(1);
        assertThat(prepared.deliveries().get(0).getStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    /**
     * The device snapshot in a PreparedDispatch predates the send. Writing it
     * back would revert a token the owner re-registered while the push was in
     * flight, so only the one field that must change is changed.
     */
    @Test
    void aDeadTokenDisablesTheDeviceByIdRatherThanReSavingAStaleSnapshot() {
        PreparedDispatch prepared = prepared(USER_A);
        NotificationDevice device = prepared.devices().get(0);
        when(pushNotificationProviderPort.send(any()))
                .thenReturn(List.of(PushResult.invalidToken("DeviceNotRegistered")));

        DispatchNotificationService.Result result = service.send(prepared);

        assertThat(result.invalidTokens()).isEqualTo(1);
        assertThat(prepared.deliveries().get(0).getStatus()).isEqualTo(DeliveryStatus.INVALID_TOKEN);
        verify(deviceRepositoryPort).disableById(device.getId());
        verify(deviceRepositoryPort, never()).save(any());
        verify(deviceRepositoryPort, never()).saveAll(any());
    }

    @Test
    void aTransientFailureLeavesTheDeliveryPendingForTheRetryPass() {
        PreparedDispatch prepared = prepared(USER_A);
        when(pushNotificationProviderPort.send(any()))
                .thenReturn(List.of(PushResult.retryable("Expo unreachable")));

        DispatchNotificationService.Result result = service.send(prepared);

        assertThat(result.retryable()).isEqualTo(1);
        assertThat(prepared.deliveries().get(0).getStatus()).isEqualTo(DeliveryStatus.PENDING);
        verify(deviceRepositoryPort, never()).disableById(any());
    }

    @Test
    void aPermanentRejectionFailsTheDeliveryWithoutDisablingTheDevice() {
        PreparedDispatch prepared = prepared(USER_A);
        when(pushNotificationProviderPort.send(any()))
                .thenReturn(List.of(PushResult.rejected("MessageTooBig")));

        assertThat(service.send(prepared).failed()).isEqualTo(1);
        assertThat(prepared.deliveries().get(0).getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(deviceRepositoryPort, never()).disableById(any());
    }

    /**
     * Counted before the provider is called, so a sender that keeps dying
     * mid-flight still exhausts its budget rather than retrying forever.
     */
    @Test
    void countsTheAttemptEvenWhenTheProviderThrows() {
        PreparedDispatch prepared = prepared(USER_A);
        when(pushNotificationProviderPort.send(any())).thenThrow(new IllegalStateException("boom"));

        DispatchNotificationService.Result result = service.send(prepared);

        assertThat(result.retryable()).isEqualTo(1);
        assertThat(prepared.deliveries().get(0).getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(prepared.deliveries().get(0).getAttemptCount()).isEqualTo(1);
    }

    /**
     * A provider that answers with fewer results than messages has told us
     * nothing about the rest; assuming they arrived would drop them silently.
     */
    @Test
    void aMissingResultIsTreatedAsRetryableRatherThanAsSuccess() {
        PreparedDispatch prepared = prepared(USER_A, USER_B);
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(PushResult.accepted("t1")));

        DispatchNotificationService.Result result = service.send(prepared);

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.retryable()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    void carriesTheDeepLinkMetadataThroughToThePushPayload() {
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(PushResult.accepted("t1")));

        service.send(prepared(USER_A));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PushMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(pushNotificationProviderPort).send(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(message -> {
            assertThat(message.title()).isEqualTo("New podcast available");
            assertThat(message.data()).containsEntry("targetType", "PODCAST")
                    .containsEntry("targetSlug", "barcelona-street-sessions-14");
        });
    }

    /** An unreadable payload costs the deep link, never the notification. */
    @Test
    void stillSendsWhenTheStoredDataPayloadCannotBeParsed() {
        Notification broken = Notification.create(TENANT, NotificationType.NEW_PODCAST,
                "New podcast available", "Episode", null, "PODCAST", "123", "not json");
        NotificationDevice device = device(USER_A, "a-phone");
        PreparedDispatch prepared = new PreparedDispatch(broken, List.of(device),
                List.of(NotificationDelivery.pending(broken.getId(), USER_A, device.getId(), PushProvider.EXPO)));
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(PushResult.accepted("t1")));

        assertThat(service.send(prepared).sent()).isEqualTo(1);
    }

    @Test
    void refusesAPreparedDispatchWhoseListsDoNotLineUp() {
        Notification notification = notification();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PreparedDispatch(
                        notification, List.of(device(USER_A, "a-phone")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PreparedDispatch prepared(UUID... userIds) {
        Notification notification = notification();
        List<NotificationDevice> devices = java.util.Arrays.stream(userIds)
                .map(userId -> device(userId, "phone-" + userId))
                .toList();
        List<NotificationDelivery> deliveries = devices.stream()
                .map(device -> NotificationDelivery.pending(notification.getId(), device.getUserId(),
                        device.getId(), PushProvider.EXPO))
                .toList();
        return new PreparedDispatch(notification, devices, deliveries);
    }

    private Notification notification() {
        return Notification.create(TENANT, NotificationType.NEW_PODCAST, "New podcast available",
                "Barcelona Street Sessions #14", null, "PODCAST", "123",
                "{\"type\":\"NEW_PODCAST\",\"targetType\":\"PODCAST\",\"targetId\":\"123\","
                        + "\"targetSlug\":\"barcelona-street-sessions-14\"}");
    }

    private NotificationDevice device(UUID userId, String identifier) {
        return NotificationDevice.register(userId, TENANT, identifier, DevicePlatform.IOS,
                PushProvider.EXPO, "ExponentPushToken[" + identifier + "]", "1.5.0", identifier);
    }
}
