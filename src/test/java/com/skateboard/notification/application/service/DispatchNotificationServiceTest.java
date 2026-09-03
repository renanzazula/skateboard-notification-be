package com.skateboard.notification.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.DispatchNotificationUseCase;
import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
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
import com.skateboard.notification.domain.model.UserNotification;
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
 * Fan-out, and what each provider outcome does to the delivery row and the
 * device behind it. This is where a wrong answer is expensive: treating a dead
 * token as retryable burns an attempt on every future notification, and
 * treating a timeout as permanent drops one silently.
 */
class DispatchNotificationServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private DeviceRepositoryPort deviceRepositoryPort;
    @Mock private NotificationRepositoryPort notificationRepositoryPort;
    @Mock private DeliveryRepositoryPort deliveryRepositoryPort;
    @Mock private PushNotificationProviderPort pushNotificationProviderPort;

    private DispatchNotificationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DispatchNotificationService(deviceRepositoryPort, notificationRepositoryPort,
                deliveryRepositoryPort, pushNotificationProviderPort, new ObjectMapper());
        when(deliveryRepositoryPort.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void sendsNothingAndTouchesNoProviderWhenNoDeviceMatches() {
        Notification notification = notification();
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of());

        DispatchNotificationUseCase.Result result = service.execute(notification);

        assertThat(result.devicesTargeted()).isZero();
        verifyNoInteractions(pushNotificationProviderPort);
        verify(notificationRepositoryPort, never()).saveRecipients(any());
    }

    /**
     * Three phones belonging to two people is two inbox entries, not three —
     * the notification is per user, the delivery is per device.
     */
    @Test
    void recordsOneRecipientPerUserRegardlessOfHowManyDevicesTheyHave() {
        Notification notification = notification();
        List<NotificationDevice> devices = List.of(
                device(USER_A, "a-phone"), device(USER_A, "a-tablet"), device(USER_B, "b-phone"));
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(devices);
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(
                PushResult.accepted("t1"), PushResult.accepted("t2"), PushResult.accepted("t3")));

        service.execute(notification);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserNotification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepositoryPort).saveRecipients(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .extracting(UserNotification::getUserId)
                .containsExactlyInAnyOrder(USER_A, USER_B);
    }

    @Test
    void anAcceptedTicketMarksTheDeliverySentWithTheProvidersId() {
        DeliveryStatus status = dispatchOne(PushResult.accepted("ticket-1")).getStatus();

        assertThat(status).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void aDeadTokenMarksTheDeliveryInvalidAndDisablesTheDevice() {
        Notification notification = notification();
        NotificationDevice device = device(USER_A, "a-phone");
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device));
        when(pushNotificationProviderPort.send(any()))
                .thenReturn(List.of(PushResult.invalidToken("DeviceNotRegistered")));

        DispatchNotificationUseCase.Result result = service.execute(notification);

        assertThat(result.invalidTokens()).isEqualTo(1);
        assertThat(device.isEnabled()).isFalse();
        verify(deviceRepositoryPort).saveAll(List.of(device));
    }

    @Test
    void aTransientFailureLeavesTheDeliveryPendingAndTheDeviceEnabled() {
        Notification notification = notification();
        NotificationDevice device = device(USER_A, "a-phone");
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device));
        when(pushNotificationProviderPort.send(any()))
                .thenReturn(List.of(PushResult.retryable("Expo unreachable")));

        service.execute(notification);

        assertThat(device.isEnabled()).isTrue();
        assertThat(capturedDelivery().getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(capturedDelivery().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void aPermanentRejectionFailsTheDeliveryWithoutDisablingTheDevice() {
        Notification notification = notification();
        NotificationDevice device = device(USER_A, "a-phone");
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device));
        when(pushNotificationProviderPort.send(any()))
                .thenReturn(List.of(PushResult.rejected("MessageTooBig")));

        DispatchNotificationUseCase.Result result = service.execute(notification);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(device.isEnabled()).isTrue();
        assertThat(capturedDelivery().getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    /**
     * A provider that answers with fewer results than messages has told us
     * nothing about the rest; assuming they arrived would drop them silently.
     */
    @Test
    void amissingResultIsTreatedAsRetryableRatherThanAsSuccess() {
        Notification notification = notification();
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device(USER_A, "a-phone"), device(USER_B, "b-phone")));
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(PushResult.accepted("t1")));

        DispatchNotificationUseCase.Result result = service.execute(notification);

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    void carriesTheDeepLinkMetadataThroughToThePushPayload() {
        Notification notification = notification();
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device(USER_A, "a-phone")));
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(PushResult.accepted("t1")));

        service.execute(notification);

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
        Notification notification = Notification.create(TENANT, NotificationType.NEW_PODCAST,
                "New podcast available", "Episode", null, "PODCAST", "123", "not json");
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device(USER_A, "a-phone")));
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(PushResult.accepted("t1")));

        assertThat(service.execute(notification).sent()).isEqualTo(1);
    }

    private NotificationDelivery dispatchOne(PushResult result) {
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device(USER_A, "a-phone")));
        when(pushNotificationProviderPort.send(any())).thenReturn(List.of(result));
        service.execute(notification());
        return capturedDelivery();
    }

    private NotificationDelivery capturedDelivery() {
        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepositoryPort).save(captor.capture());
        return captor.getValue();
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
