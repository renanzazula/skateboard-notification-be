package com.skateboard.notification.application.port.out;

/**
 * What the provider says about a message it accepted earlier.
 *
 * <p>Accepting a message and delivering it are different events, and some
 * failures are only ever reported here — Expo documents `DeviceNotRegistered`
 * as one of them. A service that never reads receipts therefore keeps a dead
 * token forever, spending a send on it for every notification.
 *
 * @param providerMessageId the ticket id handed back at send time
 * @param outcome           what the provider now knows
 * @param detail            short human-readable reason, safe to log and store
 */
public record PushReceipt(String providerMessageId, Outcome outcome, String detail) {

    public enum Outcome {
        /** Confirmed delivered to the platform's push service. */
        DELIVERED,
        /** The token is dead; the device registration should be disabled. */
        INVALID_TOKEN,
        /** Permanently failed after acceptance. */
        FAILED,
        /** Not available yet — ask again later. Never a failure. */
        NOT_READY
    }

    public static PushReceipt delivered(String providerMessageId) {
        return new PushReceipt(providerMessageId, Outcome.DELIVERED, null);
    }

    public static PushReceipt invalidToken(String providerMessageId, String detail) {
        return new PushReceipt(providerMessageId, Outcome.INVALID_TOKEN, detail);
    }

    public static PushReceipt failed(String providerMessageId, String detail) {
        return new PushReceipt(providerMessageId, Outcome.FAILED, detail);
    }

    public static PushReceipt notReady(String providerMessageId) {
        return new PushReceipt(providerMessageId, Outcome.NOT_READY, null);
    }
}
