package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.PushNotificationProviderPort;
import com.skateboard.notification.application.port.out.PushReceipt;
import com.skateboard.notification.domain.model.DeliveryStatus;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.PushProvider;
import com.skateboard.notification.infrastructure.push.ReceiptProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Acceptance is not delivery, and this is the pass that learns the difference.
 *
 * <p>The case that matters most is the dead token: Expo reports
 * `DeviceNotRegistered` only in the receipt, never at send time, so without
 * this the device stays enabled and burns a message on every future
 * notification.
 */
class PollDeliveryReceiptsServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private DeliveryRepositoryPort deliveryRepositoryPort;
    @Mock private DeviceRepositoryPort deviceRepositoryPort;
    @Mock private PushNotificationProviderPort pushNotificationProviderPort;

    private PollDeliveryReceiptsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PollDeliveryReceiptsService(deliveryRepositoryPort, deviceRepositoryPort,
                pushNotificationProviderPort, new ReceiptProperties(true, 900, 24, 100));
        when(deliveryRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void doesNothingWhenNoDeliveryIsAwaitingAReceipt() {
        when(deliveryRepositoryPort.findAwaitingReceipt(any(), any(), anyInt())).thenReturn(List.of());

        assertThat(service.run()).isZero();
        verifyNoInteractions(pushNotificationProviderPort);
    }

    @Test
    void confirmsADeliveredMessage() {
        NotificationDelivery delivery = sent("ticket-1");
        awaiting(delivery);
        when(pushNotificationProviderPort.fetchReceipts(any()))
                .thenReturn(List.of(PushReceipt.delivered("ticket-1")));

        assertThat(service.run()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    /** The reason this pass exists. */
    @Test
    void aDeadTokenReportedOnlyInTheReceiptDisablesTheDevice() {
        NotificationDelivery delivery = sent("ticket-1");
        awaiting(delivery);
        when(pushNotificationProviderPort.fetchReceipts(any()))
                .thenReturn(List.of(PushReceipt.invalidToken("ticket-1", "DeviceNotRegistered")));

        assertThat(service.run()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.INVALID_TOKEN);
        verify(deviceRepositoryPort).disableById(delivery.getDeviceId());
    }

    @Test
    void aReportedFailureIsRecordedWithoutTouchingTheDevice() {
        NotificationDelivery delivery = sent("ticket-1");
        awaiting(delivery);
        when(pushNotificationProviderPort.fetchReceipts(any()))
                .thenReturn(List.of(PushReceipt.failed("ticket-1", "MessageTooBig")));

        assertThat(service.run()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(deviceRepositoryPort, never()).disableById(any());
    }

    /**
     * A receipt that has not been produced yet is not a failure. Settling the
     * row here would retire a delivery that is still perfectly in flight.
     */
    @Test
    void leavesADeliveryAloneWhileTheProviderHasNoAnswerYet() {
        NotificationDelivery delivery = sent("ticket-1");
        awaiting(delivery);
        when(pushNotificationProviderPort.fetchReceipts(any()))
                .thenReturn(List.of(PushReceipt.notReady("ticket-1")));

        assertThat(service.run()).isZero();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
        verify(deliveryRepositoryPort, never()).save(any());
    }

    @Test
    void ignoresAReceiptForAMessageThisPassDidNotAskAbout() {
        awaiting(sent("ticket-1"));
        when(pushNotificationProviderPort.fetchReceipts(any()))
                .thenReturn(List.of(PushReceipt.delivered("some-other-ticket")));

        assertThat(service.run()).isZero();
        verify(deliveryRepositoryPort, never()).save(any());
    }

    private void awaiting(NotificationDelivery delivery) {
        when(deliveryRepositoryPort.findAwaitingReceipt(any(), any(), anyInt()))
                .thenReturn(List.of(delivery));
    }

    private NotificationDelivery sent(String providerMessageId) {
        NotificationDelivery delivery = NotificationDelivery.pending(
                UUID.randomUUID(), USER, UUID.randomUUID(), PushProvider.EXPO);
        delivery.beginAttempt();
        delivery.markSent(providerMessageId);
        return delivery;
    }
}
