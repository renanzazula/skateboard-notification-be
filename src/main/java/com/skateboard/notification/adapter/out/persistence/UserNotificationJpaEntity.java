package com.skateboard.notification.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_notification")
public class UserNotificationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserNotificationJpaEntity() {
        // required by JPA
    }

    public UUID getId()             { return id; }
    public UUID getNotificationId() { return notificationId; }
    public UUID getUserId()         { return userId; }
    public Instant getReadAt()      { return readAt; }
    public Instant getCreatedAt()   { return createdAt; }

    public void setId(UUID id)                          { this.id = id; }
    public void setNotificationId(UUID notificationId)  { this.notificationId = notificationId; }
    public void setUserId(UUID userId)                  { this.userId = userId; }
    public void setReadAt(Instant readAt)               { this.readAt = readAt; }
    public void setCreatedAt(Instant createdAt)         { this.createdAt = createdAt; }
}
