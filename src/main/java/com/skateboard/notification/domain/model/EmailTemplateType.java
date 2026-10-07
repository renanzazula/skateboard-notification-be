package com.skateboard.notification.domain.model;

/**
 * Mirrors skateboard-app-config-be's {@code EmailTemplateType} by name — the
 * two services agree on this set out of band (same as {@code NotificationType}
 * agreeing with the mobile app's deep-link routing), not via a shared
 * library. Used as the path segment when calling
 * {@code GET /api/email-templates/{type}/{language}}.
 */
public enum EmailTemplateType {
    GUEST_APPLICATION_RECEIVED,
    GUEST_APPLICATION_ADMIN_NOTIFICATION
}
