package com.skateboard.notification.adapter.in.rest;

import com.skateboard.notification.application.port.in.RegisterDeviceUseCase;
import com.skateboard.notification.application.port.in.RemoveDeviceUseCase;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.PushProvider;
import com.skateboard.notification.infrastructure.security.CurrentUser;
import com.skateboard.notification.infrastructure.security.CurrentUserProvider;
import com.skateboard.notification.infrastructure.web.api.DevicesApi;
import com.skateboard.notification.infrastructure.web.dto.DeviceResponse;
import com.skateboard.notification.infrastructure.web.dto.RegisterDeviceRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * Device registration, scoped to the caller.
 *
 * <p>The authority check keeps unauthenticated and guest sessions out; the
 * scoping to the JWT's subject in {@link CurrentUserProvider} is what makes it
 * self-service. Neither user nor tenant is ever read from the request, which
 * is the whole security property of this endpoint (spec §10, §28).
 */
@RestController
public class NotificationDeviceController implements DevicesApi {

    private static final String DEVICE_MANAGE = "hasAuthority('FUNC_NOTIFICATION_DEVICE_MANAGE')";

    private final RegisterDeviceUseCase registerDeviceUseCase;
    private final RemoveDeviceUseCase removeDeviceUseCase;
    private final CurrentUserProvider currentUserProvider;

    public NotificationDeviceController(RegisterDeviceUseCase registerDeviceUseCase,
                                         RemoveDeviceUseCase removeDeviceUseCase,
                                         CurrentUserProvider currentUserProvider) {
        this.registerDeviceUseCase = registerDeviceUseCase;
        this.removeDeviceUseCase = removeDeviceUseCase;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    @PreAuthorize(DEVICE_MANAGE)
    public ResponseEntity<DeviceResponse> registerDevice(String deviceIdentifier,
                                                          RegisterDeviceRequest registerDeviceRequest) {
        CurrentUser caller = currentUserProvider.require();
        NotificationDevice device = registerDeviceUseCase.execute(new RegisterDeviceUseCase.Input(
                caller.id(),
                caller.tenantId(),
                deviceIdentifier,
                DevicePlatform.valueOf(registerDeviceRequest.getPlatform().getValue()),
                resolveProvider(registerDeviceRequest),
                registerDeviceRequest.getPushToken(),
                registerDeviceRequest.getAppVersion(),
                registerDeviceRequest.getDeviceName()));
        return ResponseEntity.ok(toResponse(device));
    }

    @Override
    @PreAuthorize(DEVICE_MANAGE)
    public ResponseEntity<Void> removeDevice(String deviceIdentifier) {
        removeDeviceUseCase.execute(currentUserProvider.require().id(), deviceIdentifier);
        return ResponseEntity.noContent().build();
    }

    /** The spec defaults provider to EXPO; older clients may omit it entirely. */
    private PushProvider resolveProvider(RegisterDeviceRequest request) {
        return request.getProvider() == null
                ? PushProvider.EXPO
                : PushProvider.valueOf(request.getProvider().getValue());
    }

    private DeviceResponse toResponse(NotificationDevice device) {
        // pushToken is deliberately absent — it is a delivery credential, and
        // the caller already has the value it just sent.
        return new DeviceResponse()
                .id(device.getId())
                .deviceIdentifier(device.getDeviceIdentifier())
                .platform(DeviceResponse.PlatformEnum.fromValue(device.getPlatform().name()))
                .provider(DeviceResponse.ProviderEnum.fromValue(device.getPushProvider().name()))
                .appVersion(device.getAppVersion())
                .deviceName(device.getDeviceName())
                .enabled(device.isEnabled())
                .lastSeenAt(device.getLastSeenAt() == null
                        ? null : device.getLastSeenAt().atOffset(ZoneOffset.UTC))
                .createdAt(device.getCreatedAt().atOffset(ZoneOffset.UTC))
                .updatedAt(device.getUpdatedAt().atOffset(ZoneOffset.UTC));
    }
}
