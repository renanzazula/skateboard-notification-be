package com.skateboard.notification.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.SendTestNotificationUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SendTestNotificationServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private DeviceRepositoryPort deviceRepositoryPort;
    @Mock private NotificationRecorder notificationRecorder;
    @Mock private DispatchNotificationService dispatchNotificationService;

    private SendTestNotificationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SendTestNotificationService(deviceRepositoryPort, new NotificationTemplateResolver(),
                notificationRecorder, dispatchNotificationService, new ObjectMapper());
    }

    @Test
    void aUserWithNoDeviceGetsAZeroResultAndNothingIsWritten() {
        when(deviceRepositoryPort.findEnabledDevicesOfUser(TENANT, USER)).thenReturn(List.of());

        SendTestNotificationUseCase.Result result =
                service.execute(new SendTestNotificationUseCase.Input(USER, TENANT));

        assertThat(result.devicesTargeted()).isZero();
        verifyNoInteractions(notificationRecorder);
        verifyNoInteractions(dispatchNotificationService);
    }

    /**
     * The devices are the caller's own, looked up by user — never the tenant
     * fan-out, which is what keeps a test from reaching anybody else and from
     * being silenced by the caller's preferences.
     */
    @Test
    void sendsToTheCallersOwnDevicesAndReportsTheProviderAnswer() {
        NotificationDevice phone = NotificationDevice.register(USER, TENANT, "install-1",
                DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[abc]", "1.0.0", "iPhone");
        when(deviceRepositoryPort.findEnabledDevicesOfUser(TENANT, USER)).thenReturn(List.of(phone));
        when(notificationRecorder.recordDirect(any(), eq(List.of(phone)))).thenAnswer(invocation -> {
            Notification draft = invocation.getArgument(0);
            return new PreparedDispatch(draft, List.of(phone), List.of(
                    NotificationDelivery.pending(draft.getId(), USER, phone.getId(), PushProvider.EXPO)));
        });
        when(dispatchNotificationService.send(any()))
                .thenReturn(new DispatchNotificationService.Result(1, 1, 0, 0, 0));

        SendTestNotificationUseCase.Result result =
                service.execute(new SendTestNotificationUseCase.Input(USER, TENANT));

        assertThat(result).isEqualTo(new SendTestNotificationUseCase.Result(1, 1, 0, 0, 0));
        verify(deviceRepositoryPort, never()).findNotifiableDevices(any(), any());

        ArgumentCaptor<Notification> draft = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRecorder).recordDirect(draft.capture(), eq(List.of(phone)));
        assertThat(draft.getValue().getType()).isEqualTo(NotificationType.TEST_NOTIFICATION);
        assertThat(draft.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(draft.getValue().getTitle()).isEqualTo("Test notification");
        assertThat(draft.getValue().getDataJson()).contains("TEST_NOTIFICATION");
    }
}
