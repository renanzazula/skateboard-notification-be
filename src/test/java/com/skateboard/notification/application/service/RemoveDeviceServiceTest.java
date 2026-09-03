package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.PushProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoveDeviceServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private DeviceRepositoryPort deviceRepositoryPort;

    private RemoveDeviceService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RemoveDeviceService(deviceRepositoryPort);
    }

    @Test
    void disablesTheDeviceRatherThanDeletingIt() {
        NotificationDevice device = NotificationDevice.register(USER, TENANT, "install-1",
                DevicePlatform.ANDROID, PushProvider.EXPO, "ExponentPushToken[abc]", "1.5.0", "Pixel");
        when(deviceRepositoryPort.findByUserAndIdentifier(USER, "install-1")).thenReturn(Optional.of(device));

        service.execute(USER, "install-1");

        ArgumentCaptor<NotificationDevice> captor = ArgumentCaptor.forClass(NotificationDevice.class);
        verify(deviceRepositoryPort).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isFalse();
    }

    /**
     * The client calls this while signing out. Failing here would fail a
     * logout over a device the server had already forgotten.
     */
    @Test
    void removingAnUnknownDeviceIsANoOpRatherThanAnError() {
        when(deviceRepositoryPort.findByUserAndIdentifier(USER, "unknown")).thenReturn(Optional.empty());

        assertThatCode(() -> service.execute(USER, "unknown")).doesNotThrowAnyException();
        verify(deviceRepositoryPort, never()).save(any());
    }
}
