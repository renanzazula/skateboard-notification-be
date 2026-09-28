package com.skateboard.notification.application.port.in;

import java.util.UUID;

public interface MarkNotificationReadUseCase {

    /**
     * @throws com.skateboard.notification.domain.exception.InboxNotificationNotFoundException
     *         when the caller never received this notification
     */
    void execute(Input input);

    record Input(UUID userId, UUID tenantId, UUID notificationId) {
    }
}
