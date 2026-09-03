package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.NotificationType;

/**
 * Counts what the service did, so "how many pushes failed last night?" is a
 * number rather than a log query (spec §31).
 *
 * <p>It is a port rather than a {@code MeterRegistry} injected into the services
 * directly, for the same reason every other outbound concern is: the
 * application layer names what happened and the adapter decides how it is
 * recorded. {@code infrastructure.metrics.MicrometerNotificationMetrics} is the
 * only class that imports Micrometer.
 */
public interface NotificationMetricsPort {

    /**
     * An event won the idempotency claim and processing began. Counted inside
     * the recorder's transaction, so a claim that then rolls back on a genuine
     * failure is a rare over-count — that same failure shows up in the DLQ.
     */
    void eventProcessed(String eventType);

    /** A redelivery of an already-processed event was recognised and dropped. */
    void eventIgnoredAsDuplicate(String eventType);

    /**
     * The outcome of one dispatch of a notification of this type — the counts
     * come straight off {@code DispatchNotificationService.Result}. Recorded at
     * that one choke point, so a first send and a retried send are counted the
     * same way.
     *
     * @param accepted     the provider took a ticket for the message
     * @param retryable    the provider did not accept it; the retry pass owns it
     * @param failed       a permanent rejection
     * @param invalidTokens a dead token; the device was disabled
     */
    void recordDeliveryOutcomes(NotificationType type, int accepted, int retryable, int failed, int invalidTokens);
}
