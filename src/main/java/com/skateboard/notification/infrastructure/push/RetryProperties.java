package com.skateboard.notification.infrastructure.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds on re-sending deliveries the provider did not accept (spec §25).
 *
 * @param enabled        whether the retry pass runs at all
 * @param maxAttempts    total attempts per delivery before it is marked FAILED;
 *                       retrying forever would keep a dead endpoint costing a
 *                       request on every pass
 * @param backoffSeconds how long after an attempt a delivery becomes eligible
 *                       again — also what keeps two instances off the same rows
 *                       once the claim's row lock is released
 * @param batchLimit     deliveries claimed per pass, so a backlog drains at a
 *                       predictable rate instead of in one burst
 */
@ConfigurationProperties(prefix = "push.retry")
public record RetryProperties(boolean enabled, int maxAttempts, long backoffSeconds, int batchLimit) {
}
