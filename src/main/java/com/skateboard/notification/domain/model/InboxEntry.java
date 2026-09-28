package com.skateboard.notification.domain.model;

import java.time.Instant;

/**
 * One line of a user's inbox: the notification as it was rendered, plus that
 * user's read state for it. A read model rather than an aggregate — the two
 * halves live in separate tables ({@code notification} and
 * {@code user_notification}) precisely so the content is stored once however
 * many people received it, and this is what joining them back looks like.
 */
public record InboxEntry(Notification notification, Instant readAt) {

    public boolean isRead() {
        return readAt != null;
    }
}
