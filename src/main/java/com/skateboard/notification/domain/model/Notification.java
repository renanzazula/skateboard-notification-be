package com.skateboard.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * What happened, rendered once for everyone it happened to.
 *
 * <p>Stored independently of delivery (spec Decision 6): a notification is a
 * user-facing fact with a life of its own — it can be listed, marked read, and
 * outlive every push attempt made for it. {@code data} is the navigation
 * payload the app switches on, kept as an opaque JSON string; the REST/push
 * layers serialize it, the domain never looks inside, matching how
 * skateboard-podcast-be treats a post's blocks.
 */
public class Notification {

    private final UUID id;
    private final UUID tenantId;
    private final NotificationType type;
    private final String title;
    private final String body;
    private final String imageUrl;
    private final String referenceType;
    private final String referenceId;
    private final String dataJson;
    private final Instant createdAt;

    private Notification(UUID id, UUID tenantId, NotificationType type, String title, String body,
                          String imageUrl, String referenceType, String referenceId, String dataJson,
                          Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.imageUrl = imageUrl;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.dataJson = dataJson == null || dataJson.isBlank() ? "{}" : dataJson;
        this.createdAt = createdAt;
    }

    public static Notification create(UUID tenantId, NotificationType type, String title, String body,
                                       String imageUrl, String referenceType, String referenceId,
                                       String dataJson) {
        return new Notification(UUID.randomUUID(), tenantId, type, title, body, imageUrl,
                referenceType, referenceId, dataJson, Instant.now());
    }

    public static Notification reconstitute(UUID id, UUID tenantId, NotificationType type, String title,
                                             String body, String imageUrl, String referenceType,
                                             String referenceId, String dataJson, Instant createdAt) {
        return new Notification(id, tenantId, type, title, body, imageUrl, referenceType, referenceId,
                dataJson, createdAt);
    }

    public UUID getId()              { return id; }
    public UUID getTenantId()        { return tenantId; }
    public NotificationType getType() { return type; }
    public String getTitle()         { return title; }
    public String getBody()          { return body; }
    public String getImageUrl()      { return imageUrl; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId()   { return referenceId; }
    public String getDataJson()      { return dataJson; }
    public Instant getCreatedAt()    { return createdAt; }
}
