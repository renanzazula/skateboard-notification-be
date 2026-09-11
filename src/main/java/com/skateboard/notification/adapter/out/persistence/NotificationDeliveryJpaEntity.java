package com.skateboard.notification.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery")
public class NotificationDeliveryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String status;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationDeliveryJpaEntity() {
        // required by JPA
    }

    public UUID getId()                  { return id; }
    public UUID getNotificationId()      { return notificationId; }
    public UUID getUserId()              { return userId; }
    public UUID getDeviceId()            { return deviceId; }
    public String getChannel()           { return channel; }
    public String getProvider()          { return provider; }
    public String getStatus()            { return status; }
    public String getProviderMessageId() { return providerMessageId; }
    public int getAttemptCount()         { return attemptCount; }
    public Instant getLastAttemptAt()    { return lastAttemptAt; }
    public String getFailureReason()     { return failureReason; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }

    public void setId(UUID id)                                     { this.id = id; }
    public void setNotificationId(UUID notificationId)             { this.notificationId = notificationId; }
    public void setUserId(UUID userId)                             { this.userId = userId; }
    public void setDeviceId(UUID deviceId)                         { this.deviceId = deviceId; }
    public void setChannel(String channel)                         { this.channel = channel; }
    public void setProvider(String provider)                       { this.provider = provider; }
    public void setStatus(String status)                           { this.status = status; }
    public void setProviderMessageId(String providerMessageId)     { this.providerMessageId = providerMessageId; }
    public void setAttemptCount(int attemptCount)                  { this.attemptCount = attemptCount; }
    public void setLastAttemptAt(Instant lastAttemptAt)            { this.lastAttemptAt = lastAttemptAt; }
    public void setFailureReason(String failureReason)             { this.failureReason = failureReason; }
    public void setCreatedAt(Instant createdAt)                    { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt)                    { this.updatedAt = updatedAt; }
}
