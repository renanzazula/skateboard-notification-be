package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
import com.skateboard.notification.application.port.out.PreferenceRepositoryPort;
import com.skateboard.notification.domain.model.DeliveryStatus;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationPreferences;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.domain.model.PushProvider;
import com.skateboard.notification.infrastructure.push.RetryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The pass that makes {@code markRetryable} mean something.
 *
 * <p>Without it a delivery the provider did not accept would sit PENDING
 * forever: the AMQP listener's retry only covers failures that reach it as
 * exceptions, and everything after the recorder commits is past that point. A
 * single Expo timeout would drop a whole fan-out while the message that caused
 * it was acked.
 */
class RetryPendingDeliveriesServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private DeliveryRepositoryPort deliveryRepositoryPort;
    @Mock private NotificationRepositoryPort notificationRepositoryPort;
    @Mock private DeviceRepositoryPort deviceRepositoryPort;
    @Mock private PreferenceRepositoryPort preferenceRepositoryPort;
    @Mock private DispatchNotificationService dispatchNotificationService;

    private RetryPendingDeliveriesService service;
    private Notification notification;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RetryPendingDeliveriesService(deliveryRepositoryPort, notificationRepositoryPort,
                deviceRepositoryPort, preferenceRepositoryPort, dispatchNotificationService,
                new RetryProperties(true, 4, 120, 100));
        notification = Notification.create(TENANT, NotificationType.NEW_PODCAST, "New podcast available",
                "Barcelona Street Sessions #14", null, "PODCAST", "123", "{}");
        when(deliveryRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dispatchNotificationService.send(any()))
                .thenReturn(new DispatchNotificationService.Result(1, 1, 0, 0, 0));
        // A user with no stored rows: absence means enabled.
        when(preferenceRepositoryPort.load(any(), any()))
                .thenAnswer(invocation -> NotificationPreferences.defaults(invocation.getArgument(0),
                        invocation.getArgument(1)));
    }

    @Test
    void doesNothingWhenThereIsNothingOwed() {
        when(deliveryRepositoryPort.claimRetryable(anyInt(), any(), anyInt())).thenReturn(List.of());

        assertThat(service.run()).isZero();
        verifyNoInteractions(dispatchNotificationService);
    }

    @Test
    void reSendsADeliveryTheProviderDidNotAccept() {
        NotificationDevice device = device();
        NotificationDelivery delivery = pending(device);
        claim(delivery);
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(deviceRepositoryPort.findById(device.getId())).thenReturn(Optional.of(device));

        assertThat(service.run()).isEqualTo(1);

        ArgumentCaptor<PreparedDispatch> captor = ArgumentCaptor.forClass(PreparedDispatch.class);
        verify(dispatchNotificationService).send(captor.capture());
        assertThat(captor.getValue().devices()).containsExactly(device);
        assertThat(captor.getValue().deliveries()).containsExactly(delivery);
    }

    /**
     * The user opted out of podcasts after the event was recorded but before
     * this pass ran. The push must not go out, and the row must be retired so
     * it is not reclaimed on every pass.
     */
    @Test
    void suppressesADeliveryWhenTheUserHasSinceDisabledThatType() {
        NotificationDevice device = device();
        NotificationDelivery delivery = pending(device);
        claim(delivery);
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(deviceRepositoryPort.findById(device.getId())).thenReturn(Optional.of(device));
        NotificationPreferences optedOut = NotificationPreferences.defaults(USER, TENANT);
        optedOut.update(null, Map.of(NotificationType.NEW_PODCAST, false));
        when(preferenceRepositoryPort.load(USER, TENANT)).thenReturn(optedOut);

        assertThat(service.run()).isZero();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getFailureReason()).contains("Suppressed");
        verifyNoInteractions(dispatchNotificationService);
    }

    /** The master switch closes every type, whatever the per-type flag says. */
    @Test
    void suppressesADeliveryWhenTheUserHasSinceDisabledPushAltogether() {
        NotificationDevice device = device();
        NotificationDelivery delivery = pending(device);
        claim(delivery);
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(deviceRepositoryPort.findById(device.getId())).thenReturn(Optional.of(device));
        NotificationPreferences pushOff = NotificationPreferences.defaults(USER, TENANT);
        pushOff.update(false, null);
        when(preferenceRepositoryPort.load(USER, TENANT)).thenReturn(pushOff);

        assertThat(service.run()).isZero();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verifyNoInteractions(dispatchNotificationService);
    }

    /** Only the opted-out user is dropped from a shared batch. */
    @Test
    void stillSendsToOtherUsersInTheSameBatch() {
        UUID otherUser = UUID.fromString("22222222-2222-2222-2222-222222222222");
        NotificationDevice optedOutDevice = device();
        NotificationDevice otherDevice = NotificationDevice.register(otherUser, TENANT, "install-2",
                DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[def]", "1.5.0", "iPhone");
        NotificationDelivery optedOutDelivery = pending(optedOutDevice);
        NotificationDelivery otherDelivery = NotificationDelivery.pending(notification.getId(), otherUser,
                otherDevice.getId(), PushProvider.EXPO);
        when(deliveryRepositoryPort.claimRetryable(anyInt(), any(), anyInt()))
                .thenReturn(List.of(optedOutDelivery, otherDelivery));
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(deviceRepositoryPort.findById(optedOutDevice.getId())).thenReturn(Optional.of(optedOutDevice));
        when(deviceRepositoryPort.findById(otherDevice.getId())).thenReturn(Optional.of(otherDevice));
        NotificationPreferences optedOut = NotificationPreferences.defaults(USER, TENANT);
        optedOut.update(null, Map.of(NotificationType.NEW_PODCAST, false));
        when(preferenceRepositoryPort.load(USER, TENANT)).thenReturn(optedOut);

        assertThat(service.run()).isEqualTo(1);

        ArgumentCaptor<PreparedDispatch> captor = ArgumentCaptor.forClass(PreparedDispatch.class);
        verify(dispatchNotificationService).send(captor.capture());
        assertThat(captor.getValue().devices()).containsExactly(otherDevice);
        assertThat(captor.getValue().deliveries()).containsExactly(otherDelivery);
        assertThat(optedOutDelivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    /**
     * A test push is a user checking the pipe works — preferences never apply,
     * on retry any more than on the first send.
     */
    @Test
    void neverAppliesPreferencesToATestNotification() {
        notification = Notification.create(TENANT, NotificationType.TEST_NOTIFICATION, "Test", "Test",
                null, "TEST", null, "{}");
        NotificationDevice device = device();
        NotificationDelivery delivery = pending(device);
        claim(delivery);
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(deviceRepositoryPort.findById(device.getId())).thenReturn(Optional.of(device));
        NotificationPreferences pushOff = NotificationPreferences.defaults(USER, TENANT);
        pushOff.update(false, null);
        when(preferenceRepositoryPort.load(USER, TENANT)).thenReturn(pushOff);

        assertThat(service.run()).isEqualTo(1);
        verify(dispatchNotificationService).send(any());
    }

    /**
     * The device is gone or was disabled — most often because its token turned
     * out to be dead, or the user signed out. Another attempt cannot succeed,
     * and leaving it PENDING would reclaim the row on every pass.
     */
    @Test
    void failsADeliveryWhoseDeviceIsNoLongerRegistered() {
        NotificationDevice device = device();
        device.disable();
        NotificationDelivery delivery = pending(device);
        claim(delivery);
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(deviceRepositoryPort.findById(device.getId())).thenReturn(Optional.of(device));

        assertThat(service.run()).isZero();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verifyNoInteractions(dispatchNotificationService);
    }

    @Test
    void failsADeliveryWhoseNotificationHasBeenDeleted() {
        NotificationDelivery delivery = pending(device());
        claim(delivery);
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.empty());

        assertThat(service.run()).isZero();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verifyNoInteractions(dispatchNotificationService);
    }

    /**
     * Retrying forever would keep a permanently unreachable endpoint costing a
     * request on every pass.
     */
    @Test
    void givesUpOnceTheAttemptBudgetIsSpent() {
        NotificationDevice device = device();
        NotificationDelivery delivery = pending(device);
        for (int i = 0; i < 4; i++) {
            delivery.beginAttempt();
        }
        delivery.markRetryable("still failing");
        when(deliveryRepositoryPort.claimRetryable(anyInt(), any(), anyInt())).thenReturn(List.of(delivery));
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(deviceRepositoryPort.findById(device.getId())).thenReturn(Optional.of(device));
        when(dispatchNotificationService.send(any()))
                .thenReturn(new DispatchNotificationService.Result(1, 0, 1, 0, 0));

        service.run();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getFailureReason()).contains("Giving up");
    }

    @Test
    void leavesADeliveryPendingWhileItStillHasAttemptsLeft() {
        NotificationDevice device = device();
        NotificationDelivery delivery = pending(device);
        delivery.beginAttempt();
        delivery.markRetryable("transient");
        when(deliveryRepositoryPort.claimRetryable(anyInt(), any(), anyInt())).thenReturn(List.of(delivery));
        when(notificationRepositoryPort.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(deviceRepositoryPort.findById(device.getId())).thenReturn(Optional.of(device));
        when(dispatchNotificationService.send(any()))
                .thenReturn(new DispatchNotificationService.Result(1, 0, 1, 0, 0));

        service.run();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    private void claim(NotificationDelivery delivery) {
        when(deliveryRepositoryPort.claimRetryable(anyInt(), any(), anyInt())).thenReturn(List.of(delivery));
    }

    private NotificationDelivery pending(NotificationDevice device) {
        return NotificationDelivery.pending(notification.getId(), USER, device.getId(), PushProvider.EXPO);
    }

    private NotificationDevice device() {
        return NotificationDevice.register(USER, TENANT, "install-1", DevicePlatform.IOS,
                PushProvider.EXPO, "ExponentPushToken[abc]", "1.5.0", "iPhone");
    }
}
