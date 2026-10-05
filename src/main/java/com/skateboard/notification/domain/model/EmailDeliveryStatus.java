package com.skateboard.notification.domain.model;

/**
 * The lifecycle of one email send attempt. Simpler than {@link DeliveryStatus}:
 * there is no device to invalidate and, for Brevo's V1 transactional API, no
 * receipt to poll for — SENT is the strongest thing known synchronously.
 */
public enum EmailDeliveryStatus {
    PENDING,
    SENT,
    FAILED
}
