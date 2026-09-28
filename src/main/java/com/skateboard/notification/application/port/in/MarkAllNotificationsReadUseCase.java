package com.skateboard.notification.application.port.in;

import java.time.Instant;
import java.util.UUID;

public interface MarkAllNotificationsReadUseCase {

    /** @return how many notifications changed from unread to read */
    int execute(Input input);

    /**
     * @param before only notifications received at or before this instant are
     *               marked; null means now
     */
    record Input(UUID userId, UUID tenantId, Instant before) {
    }
}
