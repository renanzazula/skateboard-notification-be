package com.skateboard.notification.domain.model;

/**
 * Which external service sends the email. Brevo is the only one today (spec
 * recommendation, .docs/README_GUEST_APPLICATION_lang.md §9); a future
 * provider adds a constant rather than changing anything the domain does
 * with it — same shape as {@link PushProvider}.
 */
public enum EmailProvider {
    BREVO
}
