package com.skateboard.notification.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_device")
public class NotificationDeviceJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_identifier", nullable = false)
    private String deviceIdentifier;

    @Column(nullable = false)
    private String platform;

    @Column(name = "push_provider", nullable = false)
    private String pushProvider;

    @Column(name = "push_token", nullable = false)
    private String pushToken;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "device_name")
    private String deviceName;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationDeviceJpaEntity() {
        // required by JPA
    }

    public UUID getId()                   { return id; }
    public UUID getUserId()               { return userId; }
    public UUID getTenantId()             { return tenantId; }
    public String getDeviceIdentifier()   { return deviceIdentifier; }
    public String getPlatform()           { return platform; }
    public String getPushProvider()       { return pushProvider; }
    public String getPushToken()          { return pushToken; }
    public String getAppVersion()         { return appVersion; }
    public String getDeviceName()         { return deviceName; }
    public boolean isEnabled()            { return enabled; }
    public Instant getLastSeenAt()        { return lastSeenAt; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getUpdatedAt()         { return updatedAt; }

    public void setId(UUID id)                                 { this.id = id; }
    public void setUserId(UUID userId)                         { this.userId = userId; }
    public void setTenantId(UUID tenantId)                     { this.tenantId = tenantId; }
    public void setDeviceIdentifier(String deviceIdentifier)   { this.deviceIdentifier = deviceIdentifier; }
    public void setPlatform(String platform)                   { this.platform = platform; }
    public void setPushProvider(String pushProvider)           { this.pushProvider = pushProvider; }
    public void setPushToken(String pushToken)                 { this.pushToken = pushToken; }
    public void setAppVersion(String appVersion)               { this.appVersion = appVersion; }
    public void setDeviceName(String deviceName)               { this.deviceName = deviceName; }
    public void setEnabled(boolean enabled)                    { this.enabled = enabled; }
    public void setLastSeenAt(Instant lastSeenAt)              { this.lastSeenAt = lastSeenAt; }
    public void setCreatedAt(Instant createdAt)                { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt)                { this.updatedAt = updatedAt; }
}
