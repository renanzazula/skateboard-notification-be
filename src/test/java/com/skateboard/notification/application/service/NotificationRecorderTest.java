package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
import com.skateboard.notification.application.port.out.ProcessedEventPort;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.domain.model.PushProvider;
import com.skateboard.notification.domain.model.UserNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
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

/**
 * The transaction boundary that stops an event being lost.
 *
 * <p>The ordering assertions here are not stylistic. Claiming the event and
 * committing that claim separately from the work meant any later failure left
 * the event marked processed with nothing written — and the redelivery was
 * then treated as a duplicate and acked, losing the notification with no
 * dead-letter entry.
 */
class NotificationRecorderTest {

    private static final UUID EVENT = UUID.fromString("4470ac44-224e-4115-a41e-bc554e91bf3d");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private ProcessedEventPort processedEventPort;
    @Mock private NotificationRepositoryPort notificationRepositoryPort;
    @Mock private DeviceRepositoryPort deviceRepositoryPort;
    @Mock private DeliveryRepositoryPort deliveryRepositoryPort;

    private NotificationRecorder recorder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        recorder = new NotificationRecorder(processedEventPort, notificationRepositoryPort,
                deviceRepositoryPort, deliveryRepositoryPort);
        when(notificationRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deliveryRepositoryPort.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void aRedeliveredEventWritesNothing() {
        when(processedEventPort.claim(EVENT, "PODCAST_PUBLISHED")).thenReturn(false);

        assertThat(recorder.record(EVENT, "PODCAST_PUBLISHED", draft())).isEmpty();

        verifyNoInteractions(notificationRepositoryPort);
        verifyNoInteractions(deviceRepositoryPort);
        verifyNoInteractions(deliveryRepositoryPort);
    }

    @Test
    void claimsTheEventBeforeWritingTheNotification() {
        when(processedEventPort.claim(EVENT, "PODCAST_PUBLISHED")).thenReturn(true);
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device(USER_A, "a-phone")));

        recorder.record(EVENT, "PODCAST_PUBLISHED", draft());

        InOrder inOrder = Mockito.inOrder(processedEventPort, notificationRepositoryPort);
        inOrder.verify(processedEventPort).claim(EVENT, "PODCAST_PUBLISHED");
        inOrder.verify(notificationRepositoryPort).save(any());
    }

    /**
     * Three phones belonging to two people is two inbox entries and three
     * delivery attempts — the notification is per user, the delivery per device.
     */
    @Test
    void recordsOneRecipientPerUserAndOneDeliveryPerDevice() {
        when(processedEventPort.claim(any(), any())).thenReturn(true);
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device(USER_A, "a-phone"), device(USER_A, "a-tablet"),
                        device(USER_B, "b-phone")));

        Optional<PreparedDispatch> prepared = recorder.record(EVENT, "PODCAST_PUBLISHED", draft());

        assertThat(prepared).isPresent();
        assertThat(prepared.get().deliveries()).hasSize(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserNotification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepositoryPort).saveRecipients(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .extracting(UserNotification::getUserId)
                .containsExactlyInAnyOrder(USER_A, USER_B);
    }

    /**
     * The notification is still recorded — it happened, and an inbox should
     * show it — but there is nothing to deliver it to.
     */
    @Test
    void recordsTheNotificationButNoDeliveriesWhenNoDeviceMatches() {
        when(processedEventPort.claim(any(), any())).thenReturn(true);
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of());

        Optional<PreparedDispatch> prepared = recorder.record(EVENT, "PODCAST_PUBLISHED", draft());

        assertThat(prepared).isPresent();
        assertThat(prepared.get().isEmpty()).isTrue();
        verify(notificationRepositoryPort).save(any());
        verify(notificationRepositoryPort, never()).saveRecipients(any());
        verify(deliveryRepositoryPort, never()).saveAll(any());
    }

    @Test
    void deliveriesStartPendingWithNoAttemptRecorded() {
        when(processedEventPort.claim(any(), any())).thenReturn(true);
        when(deviceRepositoryPort.findNotifiableDevices(TENANT, NotificationType.NEW_PODCAST))
                .thenReturn(List.of(device(USER_A, "a-phone")));

        PreparedDispatch prepared = recorder.record(EVENT, "PODCAST_PUBLISHED", draft()).orElseThrow();

        assertThat(prepared.deliveries()).singleElement().satisfies(delivery -> {
            assertThat(delivery.getStatus())
                    .isEqualTo(com.skateboard.notification.domain.model.DeliveryStatus.PENDING);
            assertThat(delivery.getAttemptCount()).isZero();
        });
    }

    private Notification draft() {
        return Notification.create(TENANT, NotificationType.NEW_PODCAST, "New podcast available",
                "Barcelona Street Sessions #14", null, "PODCAST", "123", "{}");
    }

    private NotificationDevice device(UUID userId, String identifier) {
        return NotificationDevice.register(userId, TENANT, identifier, DevicePlatform.IOS,
                PushProvider.EXPO, "ExponentPushToken[" + identifier + "]", "1.5.0", identifier);
    }
}
