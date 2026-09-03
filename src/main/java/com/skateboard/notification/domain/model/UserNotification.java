package com.skateboard.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The link between a notification and one recipient, and the only place read
 * state is recorded. One notification points at many of these rather than
 * being copied per user (spec §15) — which is also what makes an in-app inbox
 * a query rather than a redesign.
 */
public class UserNotification {

    private final UUID id;
    private final UUID notificationId;
    private final UUID userId;
    private Instant readAt;
    private final Instant createdAt;

    private UserNotification(UUID id, UUID notificationId, UUID userId, Instant readAt, Instant createdAt) {
        this.id = id;
        this.notificationId = notificationId;
        this.userId = userId;
        this.readAt = readAt;
        this.createdAt = createdAt;
    }

    public static UserNotification create(UUID notificationId, UUID userId) {
        return new UserNotification(UUID.randomUUID(), notificationId, userId, null, Instant.now());
    }

    public static UserNotification reconstitute(UUID id, UUID notificationId, UUID userId,
                                                 Instant readAt, Instant createdAt) {
        return new UserNotification(id, notificationId, userId, readAt, createdAt);
    }

    /** Idempotent: re-reading something does not move the timestamp. */
    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public UUID getId()             { return id; }
    public UUID getNotificationId() { return notificationId; }
    public UUID getUserId()         { return userId; }
    public Instant getReadAt()      { return readAt; }
    public Instant getCreatedAt()   { return createdAt; }
}
