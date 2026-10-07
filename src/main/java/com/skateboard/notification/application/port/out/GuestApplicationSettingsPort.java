package com.skateboard.notification.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads skateboard-app-config-be's Guest Application settings at delivery
 * time — recipients are deliberately <strong>not</strong> snapshotted on the
 * event (see skateboard-app-config-be's {@code GuestApplicationConfig}
 * javadoc, and skateboard-podcast-be's {@code GuestApplicationSubmissionNotifier}):
 * an admin changing the recipient list must take effect on the next
 * delivery, including a retry, not just new submissions.
 *
 * <p>The confirmation/admin-notification email copy used to live here too
 * (confirmationSubject/confirmationBody); it is now resolved separately via
 * {@link EmailTemplateResolverPort}, which supports per-language copy
 * instead of English only.
 */
public interface GuestApplicationSettingsPort {

    record Settings(boolean enabled, List<UUID> recipientIds) {
    }

    /**
     * @return empty when app-config-be could not be reached or answered with
     *         an unexpected shape — never thrown, so a settings outage
     *         degrades to "no admin recipients this pass" rather than losing
     *         the applicant's confirmation email too
     */
    Optional<Settings> getSettings();
}
