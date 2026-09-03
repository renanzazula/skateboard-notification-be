package com.skateboard.notification.application.port.in;

import com.skateboard.notification.domain.model.Notification;

public interface DispatchNotificationUseCase {

    record Result(int devicesTargeted, int sent, int failed, int invalidTokens) {
    }

    /**
     * Fans a persisted notification out to every device that should receive it
     * and records what happened to each attempt.
     */
    Result execute(Notification notification);
}
