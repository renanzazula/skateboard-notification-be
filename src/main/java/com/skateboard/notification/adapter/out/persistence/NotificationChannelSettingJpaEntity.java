package com.skateboard.notification.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_channel_setting")
public class NotificationChannelSettingJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationChannelSettingJpaEntity() {
    }

    public UUID getUserId()        { return userId; }
    public UUID getTenantId()      { return tenantId; }
    public boolean isPushEnabled() { return pushEnabled; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }

    public void setUserId(UUID userId)             { this.userId = userId; }
    public void setTenantId(UUID tenantId)         { this.tenantId = tenantId; }
    public void setPushEnabled(boolean pushEnabled) { this.pushEnabled = pushEnabled; }
    public void setCreatedAt(Instant createdAt)    { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt)    { this.updatedAt = updatedAt; }
}
