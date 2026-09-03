package com.skateboard.notification.application.port.in;

import java.time.Instant;
import java.util.UUID;

public interface HandlePodcastPublishedUseCase {

    record Input(UUID eventId,
                 UUID tenantId,
                 Instant occurredAt,
                 String podcastId,
                 String slug,
                 String title,
                 String imageUrl) {
    }

    record Result(boolean processed, int devicesTargeted, int sent) {

        /** The event had already been handled; nothing was written or sent. */
        public static Result duplicate() {
            return new Result(false, 0, 0);
        }
    }

    Result execute(Input input);
}
