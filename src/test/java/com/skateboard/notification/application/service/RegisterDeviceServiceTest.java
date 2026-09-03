package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.RegisterDeviceUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.PushProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration is the one write path a client drives directly, so what it does
 * with an already-known device and with a token another account still holds is
 * worth pinning down.
 */
class RegisterDeviceServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TOKEN = "ExponentPushToken[abc]";

    @Mock private DeviceRepositoryPort deviceRepositoryPort;

    private RegisterDeviceService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RegisterDeviceService(deviceRepositoryPort);
        when(deviceRepositoryPort.findOtherUsersWithPushToken(anyString(), any())).thenReturn(List.of());
        when(deviceRepositoryPort.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void registersAnUnknownDeviceAgainstTheCallerAndTheirTenant() {
        when(deviceRepositoryPort.findByUserAndIdentifier(USER, "install-1")).thenReturn(Optional.empty());

        NotificationDevice device = service.execute(input(TOKEN, "1.5.0"));

        assertThat(device.getUserId()).isEqualTo(USER);
        assertThat(device.getTenantId()).isEqualTo(TENANT);
        assertThat(device.getDeviceIdentifier()).isEqualTo("install-1");
        assertThat(device.getPushToken()).isEqualTo(TOKEN);
        assertThat(device.isEnabled()).isTrue();
    }

    @Test
    void reRegisteringTheSameInstallUpdatesItInPlaceRatherThanAddingAnother() {
        NotificationDevice existing = NotificationDevice.register(USER, TENANT, "install-1",
                DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[old]", "1.0.0", "iPhone");
        when(deviceRepositoryPort.findByUserAndIdentifier(USER, "install-1")).thenReturn(Optional.of(existing));

        NotificationDevice device = service.execute(input(TOKEN, "1.5.0"));

        assertThat(device.getId()).isEqualTo(existing.getId());
        assertThat(device.getPushToken()).isEqualTo(TOKEN);
        assertThat(device.getAppVersion()).isEqualTo("1.5.0");
    }

    @Test
    void signingInAgainReEnablesADeviceThatLogoutHadDisabled() {
        NotificationDevice existing = NotificationDevice.register(USER, TENANT, "install-1",
                DevicePlatform.IOS, PushProvider.EXPO, TOKEN, "1.5.0", "iPhone");
        existing.disable();
        when(deviceRepositoryPort.findByUserAndIdentifier(USER, "install-1")).thenReturn(Optional.of(existing));

        assertThat(service.execute(input(TOKEN, "1.5.0")).isEnabled()).isTrue();
    }

    @Test
    void claimingAPushTokenAnotherUserHoldsDisablesTheirRegistration() {
        NotificationDevice strayDevice = NotificationDevice.register(OTHER_USER, TENANT, "install-1",
                DevicePlatform.IOS, PushProvider.EXPO, TOKEN, "1.5.0", "iPhone");
        when(deviceRepositoryPort.findOtherUsersWithPushToken(TOKEN, USER)).thenReturn(List.of(strayDevice));
        when(deviceRepositoryPort.findByUserAndIdentifier(USER, "install-1")).thenReturn(Optional.empty());

        service.execute(input(TOKEN, "1.5.0"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationDevice>> captor = ArgumentCaptor.forClass(List.class);
        verify(deviceRepositoryPort).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .satisfies(disabled -> {
                    assertThat(disabled.getUserId()).isEqualTo(OTHER_USER);
                    assertThat(disabled.isEnabled()).isFalse();
                });
    }

    @Test
    void doesNotTouchOtherRegistrationsWhenTheTokenIsUnclaimed() {
        when(deviceRepositoryPort.findByUserAndIdentifier(USER, "install-1")).thenReturn(Optional.empty());

        service.execute(input(TOKEN, "1.5.0"));

        verify(deviceRepositoryPort, never()).saveAll(any());
    }

    private RegisterDeviceUseCase.Input input(String pushToken, String appVersion) {
        return new RegisterDeviceUseCase.Input(USER, TENANT, "install-1", DevicePlatform.IOS,
                PushProvider.EXPO, pushToken, appVersion, "iPhone");
    }
}
