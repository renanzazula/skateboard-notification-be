package com.skateboard.notification.application.port.in;

import java.util.UUID;

public interface RemoveDeviceUseCase {

    /**
     * Idempotent: removing an unknown or already-disabled device is a no-op,
     * not an error. The client calls this during logout and a sign-out must
     * not fail because the server had already forgotten the device.
     */
    void execute(UUID userId, String deviceIdentifier);
}
