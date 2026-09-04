package com.skateboard.notification.infrastructure.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds on asking the provider what became of messages it accepted.
 *
 * @param enabled       whether the receipt pass runs at all
 * @param minAgeSeconds how long to leave a message before asking — receipts do
 *                      not exist immediately, and asking too early just returns
 *                      NOT_READY and burns a request
 * @param maxAgeHours   how far back to look. Expo discards receipts after about
 *                      a day, so beyond this the answer will never arrive and
 *                      the row would be re-read on every pass forever
 * @param batchLimit    deliveries examined per pass
 */
@ConfigurationProperties(prefix = "push.receipts")
public record ReceiptProperties(boolean enabled, long minAgeSeconds, long maxAgeHours, int batchLimit) {
}
