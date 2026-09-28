package com.skateboard.notification.domain.exception;

import java.util.UUID;

/**
 * The caller has no inbox entry for this notification — it does not exist, or
 * it was addressed to somebody else. Deliberately one exception for both, so a
 * 404 says nothing about notifications the caller cannot see.
 */
public class InboxNotificationNotFoundException extends RuntimeException {

    public InboxNotificationNotFoundException(UUID notificationId) {
        super("Notification not found: " + notificationId);
    }
}
