package com.skateboard.notification.application.port.in;

import java.util.UUID;

public interface SendTestNotificationUseCase {

    record Input(UUID userId, UUID tenantId) {
    }

    /**
     * The provider's immediate answer per device — acceptance, not delivery.
     * {@code devicesTargeted == 0} means the user has no enabled device, which
     * is itself the diagnosis.
     */
    record Result(int devicesTargeted, int sent, int retryable, int failed, int invalidTokens) {
    }

    Result execute(Input input);
}
