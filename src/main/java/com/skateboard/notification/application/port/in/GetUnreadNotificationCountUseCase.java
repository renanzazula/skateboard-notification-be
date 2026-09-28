package com.skateboard.notification.application.port.in;

import java.util.UUID;

public interface GetUnreadNotificationCountUseCase {

    long execute(UUID userId, UUID tenantId);
}
