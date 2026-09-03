package com.skateboard.notification.domain.model;

/**
 * The kinds of notification this platform can produce. Only NEW_PODCAST is
 * wired end to end; the rest are named here because the preference model, the
 * template resolver and the event bindings are all keyed by this enum, and
 * naming them up front is what keeps adding one a matter of a template plus a
 * binding rather than a schema change.
 */
public enum NotificationType {
    NEW_PODCAST,
    NEW_POST,
    NEW_MAGAZINE,
    FEATURED_CONTENT,
    ADMIN_ANNOUNCEMENT,
    COMMENT_REPLY,
    NEW_FOLLOWER,
    EVENT_REMINDER,
    SYSTEM_NOTIFICATION
}
