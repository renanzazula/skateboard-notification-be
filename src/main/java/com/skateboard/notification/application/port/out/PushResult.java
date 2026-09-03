package com.skateboard.notification.application.port.out;

/**
 * What the provider said about one message, reduced to the four outcomes that
 * lead to different behaviour here.
 *
 * @param outcome           what to do next
 * @param providerMessageId provider-side id when accepted, else null
 * @param detail            short human-readable reason, safe to log and store
 */
public record PushResult(Outcome outcome, String providerMessageId, String detail) {

    public enum Outcome {
        /** Accepted by the provider. */
        ACCEPTED,
        /** Transient — a timeout, a 5xx, rate limiting. Worth another attempt. */
        RETRYABLE,
        /** The token is dead; the device registration should be disabled. */
        INVALID_TOKEN,
        /** Permanently rejected. Retrying will not change the answer. */
        REJECTED
    }

    public static PushResult accepted(String providerMessageId) {
        return new PushResult(Outcome.ACCEPTED, providerMessageId, null);
    }

    public static PushResult retryable(String detail) {
        return new PushResult(Outcome.RETRYABLE, null, detail);
    }

    public static PushResult invalidToken(String detail) {
        return new PushResult(Outcome.INVALID_TOKEN, null, detail);
    }

    public static PushResult rejected(String detail) {
        return new PushResult(Outcome.REJECTED, null, detail);
    }
}
