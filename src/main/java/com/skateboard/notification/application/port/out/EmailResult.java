package com.skateboard.notification.application.port.out;

/**
 * What the provider said about one email, reduced to the outcomes that lead
 * to different behaviour here — the email counterpart of {@link PushResult},
 * minus INVALID_TOKEN: an address has no token to invalidate.
 *
 * @param outcome           what to do next
 * @param providerMessageId provider-side id when accepted, else null
 * @param detail            short human-readable reason, safe to log and store
 */
public record EmailResult(Outcome outcome, String providerMessageId, String detail) {

    public enum Outcome {
        /** Accepted by the provider. */
        ACCEPTED,
        /** Transient — a timeout, a 5xx, rate limiting. Worth another attempt. */
        RETRYABLE,
        /** Permanently rejected (e.g. malformed address). Retrying will not change the answer. */
        REJECTED
    }

    public static EmailResult accepted(String providerMessageId) {
        return new EmailResult(Outcome.ACCEPTED, providerMessageId, null);
    }

    public static EmailResult retryable(String detail) {
        return new EmailResult(Outcome.RETRYABLE, null, detail);
    }

    public static EmailResult rejected(String detail) {
        return new EmailResult(Outcome.REJECTED, null, detail);
    }
}
