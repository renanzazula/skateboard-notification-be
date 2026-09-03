package com.skateboard.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One push destination: a physical device belonging to one user in one tenant.
 *
 * <p>Tokens live here rather than on the user because a user has several
 * devices and each token changes independently of the others (spec §9). The
 * device identifier is chosen and kept stable by the client per install, so
 * re-registering after a token rotation updates this row instead of growing a
 * new one per app launch.
 */
public class NotificationDevice {

    private final UUID id;
    private final UUID userId;
    private final UUID tenantId;
    private final String deviceIdentifier;
    private DevicePlatform platform;
    private PushProvider pushProvider;
    private String pushToken;
    private String appVersion;
    private String deviceName;
    private boolean enabled;
    private Instant lastSeenAt;
    private final Instant createdAt;
    private Instant updatedAt;

    private NotificationDevice(UUID id, UUID userId, UUID tenantId, String deviceIdentifier,
                                DevicePlatform platform, PushProvider pushProvider, String pushToken,
                                String appVersion, String deviceName, boolean enabled, Instant lastSeenAt,
                                Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId;
        this.deviceIdentifier = deviceIdentifier;
        this.platform = platform;
        this.pushProvider = pushProvider;
        this.pushToken = pushToken;
        this.appVersion = appVersion;
        this.deviceName = deviceName;
        this.enabled = enabled;
        this.lastSeenAt = lastSeenAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static NotificationDevice register(UUID userId, UUID tenantId, String deviceIdentifier,
                                               DevicePlatform platform, PushProvider pushProvider,
                                               String pushToken, String appVersion, String deviceName) {
        Instant now = Instant.now();
        return new NotificationDevice(UUID.randomUUID(), userId, tenantId, deviceIdentifier,
                platform, pushProvider, pushToken, appVersion, deviceName, true, now, now, now);
    }

    public static NotificationDevice reconstitute(UUID id, UUID userId, UUID tenantId, String deviceIdentifier,
                                                   DevicePlatform platform, PushProvider pushProvider,
                                                   String pushToken, String appVersion, String deviceName,
                                                   boolean enabled, Instant lastSeenAt,
                                                   Instant createdAt, Instant updatedAt) {
        return new NotificationDevice(id, userId, tenantId, deviceIdentifier, platform, pushProvider,
                pushToken, appVersion, deviceName, enabled, lastSeenAt, createdAt, updatedAt);
    }

    /**
     * Re-registration of an existing device: refresh what the client told us
     * and mark it live again. A device that was disabled on logout comes back
     * enabled here, which is exactly what signing in again should do.
     */
    public void refresh(DevicePlatform platform, PushProvider pushProvider, String pushToken,
                        String appVersion, String deviceName) {
        this.platform = platform;
        this.pushProvider = pushProvider;
        this.pushToken = pushToken;
        this.appVersion = appVersion;
        this.deviceName = deviceName;
        this.enabled = true;
        this.lastSeenAt = Instant.now();
        this.updatedAt = this.lastSeenAt;
    }

    /**
     * Stop delivering to this device — on logout, or when the provider tells
     * us the token is dead. The row survives so its delivery history stays
     * readable (spec §11).
     */
    public void disable() {
        if (!this.enabled) {
            return;
        }
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    public UUID getId()                     { return id; }
    public UUID getUserId()                 { return userId; }
    public UUID getTenantId()               { return tenantId; }
    public String getDeviceIdentifier()     { return deviceIdentifier; }
    public DevicePlatform getPlatform()     { return platform; }
    public PushProvider getPushProvider()   { return pushProvider; }
    public String getPushToken()            { return pushToken; }
    public String getAppVersion()           { return appVersion; }
    public String getDeviceName()           { return deviceName; }
    public boolean isEnabled()              { return enabled; }
    public Instant getLastSeenAt()          { return lastSeenAt; }
    public Instant getCreatedAt()           { return createdAt; }
    public Instant getUpdatedAt()           { return updatedAt; }
}
