package com.skateboard.notification.domain.model;

/**
 * The lifecycle of one delivery attempt to one device.
 *
 * <p>SENT means the provider accepted the message, which is the strongest
 * thing Expo tells us synchronously — it is not proof the handset received it
 * and it is certainly not proof anyone read it. DELIVERED exists for a future
 * receipt-polling pass; nothing sets it today. Read state lives on
 * {@code user_notification.read_at} and nowhere else.
 *
 * <p>INVALID_TOKEN is split out from FAILED because it is the one failure that
 * is permanent and actionable: the device registration is disabled rather than
 * retried.
 */
public enum DeliveryStatus {
    PENDING,
    SENT,
    DELIVERED,
    FAILED,
    INVALID_TOKEN
}
