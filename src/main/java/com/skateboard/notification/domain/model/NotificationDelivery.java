package com.skateboard.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to get one notification onto one device through one provider.
 *
 * <p>This is the infrastructure half of the split described on
 * {@link Notification}: it churns, it retries, and it fails, none of which
 * should touch the notification the user sees. Three devices for one user
 * means three rows and one {@link UserNotification}.
 */
public class NotificationDelivery {

    private final UUID id;
    private final UUID notificationId;
    private final UUID userId;
    private final UUID deviceId;
    private final DeliveryChannel channel;
    private final PushProvider provider;
    private DeliveryStatus status;
    private String providerMessageId;
    private int attemptCount;
    private Instant lastAttemptAt;
    private String failureReason;
    private final Instant createdAt;
    private Instant updatedAt;

    private NotificationDelivery(UUID id, UUID notificationId, UUID userId, UUID deviceId,
                                  DeliveryChannel channel, PushProvider provider, DeliveryStatus status,
                                  String providerMessageId, int attemptCount, Instant lastAttemptAt,
                                  String failureReason, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.notificationId = notificationId;
        this.userId = userId;
        this.deviceId = deviceId;
        this.channel = channel;
        this.provider = provider;
        this.status = status;
        this.providerMessageId = providerMessageId;
        this.attemptCount = attemptCount;
        this.lastAttemptAt = lastAttemptAt;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static NotificationDelivery pending(UUID notificationId, UUID userId, UUID deviceId,
                                                PushProvider provider) {
        Instant now = Instant.now();
        return new NotificationDelivery(UUID.randomUUID(), notificationId, userId, deviceId,
                DeliveryChannel.PUSH, provider, DeliveryStatus.PENDING, null, 0, null, null, now, now);
    }

    public static NotificationDelivery reconstitute(UUID id, UUID notificationId, UUID userId, UUID deviceId,
                                                     DeliveryChannel channel, PushProvider provider,
                                                     DeliveryStatus status, String providerMessageId,
                                                     int attemptCount, Instant lastAttemptAt,
                                                     String failureReason, Instant createdAt,
                                                     Instant updatedAt) {
        return new NotificationDelivery(id, notificationId, userId, deviceId, channel, provider, status,
                providerMessageId, attemptCount, lastAttemptAt, failureReason, createdAt, updatedAt);
    }

    /** The provider accepted the message. Not "delivered", and never "read". */
    public void markSent(String providerMessageId) {
        this.status = DeliveryStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.failureReason = null;
        recordAttempt();
    }

    /**
     * A failure worth another go later — a timeout, a 5xx, rate limiting. The
     * row stays PENDING so a retry pass can pick it up; only the attempt
     * counter moves.
     */
    public void markRetryable(String failureReason) {
        this.status = DeliveryStatus.PENDING;
        this.failureReason = failureReason;
        recordAttempt();
    }

    /** A permanent rejection. No retry will change the answer. */
    public void markFailed(String failureReason) {
        this.status = DeliveryStatus.FAILED;
        this.failureReason = failureReason;
        recordAttempt();
    }

    /** The token is dead — the caller is expected to disable the device too. */
    public void markInvalidToken(String failureReason) {
        this.status = DeliveryStatus.INVALID_TOKEN;
        this.failureReason = failureReason;
        recordAttempt();
    }

    private void recordAttempt() {
        this.attemptCount++;
        this.lastAttemptAt = Instant.now();
        this.updatedAt = this.lastAttemptAt;
    }

    public UUID getId()                  { return id; }
    public UUID getNotificationId()      { return notificationId; }
    public UUID getUserId()              { return userId; }
    public UUID getDeviceId()            { return deviceId; }
    public DeliveryChannel getChannel()  { return channel; }
    public PushProvider getProvider()    { return provider; }
    public DeliveryStatus getStatus()    { return status; }
    public String getProviderMessageId() { return providerMessageId; }
    public int getAttemptCount()         { return attemptCount; }
    public Instant getLastAttemptAt()    { return lastAttemptAt; }
    public String getFailureReason()     { return failureReason; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }
}
