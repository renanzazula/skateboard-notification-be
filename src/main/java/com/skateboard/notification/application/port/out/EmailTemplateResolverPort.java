package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.EmailTemplateType;

import java.util.Optional;

/**
 * Reads skateboard-app-config-be's admin-configured email copy at delivery
 * time — same "live, never snapshotted" reasoning as
 * {@link GuestApplicationSettingsPort}: an admin editing a template must take
 * effect on the next delivery, including a retry.
 */
public interface EmailTemplateResolverPort {

    record Template(String subject, String body, boolean enabled) {
    }

    /**
     * @return empty when app-config-be could not be reached or answered with
     *         an unexpected shape — never thrown, so a resolver outage
     *         degrades to the caller's own hardcoded fallback copy rather
     *         than losing the email entirely
     */
    Optional<Template> resolve(EmailTemplateType type, String language);
}
