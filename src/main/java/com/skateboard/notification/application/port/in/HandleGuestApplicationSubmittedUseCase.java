package com.skateboard.notification.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HandleGuestApplicationSubmittedUseCase {

    record Input(UUID eventId,
                 UUID tenantId,
                 Instant occurredAt,
                 String applicationId,
                 String userId,
                 String name,
                 String email,
                 String message,
                 List<String> socialLinks) {
    }

    record Result(boolean processed, int adminsNotifiedInApp, int emailsQueued) {

        /** The event had already been handled; nothing was written or sent. */
        public static Result duplicate() {
            return new Result(false, 0, 0);
        }
    }

    Result execute(Input input);
}
