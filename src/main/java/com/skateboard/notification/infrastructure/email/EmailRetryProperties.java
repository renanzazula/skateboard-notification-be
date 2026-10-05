package com.skateboard.notification.infrastructure.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link com.skateboard.notification.application.service.RetryPendingEmailDeliveriesService},
 * mirroring {@code push.retry}'s {@code RetryProperties}.
 */
@ConfigurationProperties(prefix = "email.retry")
public record EmailRetryProperties(int maxAttempts, int backoffSeconds, int batchLimit) {
}
