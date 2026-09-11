package com.skateboard.notification.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class NotificationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "reference_type")
    private String referenceType;

    @Column(name = "reference_id")
    private String referenceId;

    @Column(name = "data", nullable = false)
    private String data;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public NotificationJpaEntity() {
        // required by JPA
    }

    public UUID getId()              { return id; }
    public UUID getTenantId()        { return tenantId; }
    public String getType()          { return type; }
    public String getTitle()         { return title; }
    public String getBody()          { return body; }
    public String getImageUrl()      { return imageUrl; }
    public String getReferenceType() { return referenceType; }
    public String getReferenceId()   { return referenceId; }
    public String getData()          { return data; }
    public Instant getCreatedAt()    { return createdAt; }

    public void setId(UUID id)                             { this.id = id; }
    public void setTenantId(UUID tenantId)                 { this.tenantId = tenantId; }
    public void setType(String type)                       { this.type = type; }
    public void setTitle(String title)                     { this.title = title; }
    public void setBody(String body)                       { this.body = body; }
    public void setImageUrl(String imageUrl)               { this.imageUrl = imageUrl; }
    public void setReferenceType(String referenceType)     { this.referenceType = referenceType; }
    public void setReferenceId(String referenceId)         { this.referenceId = referenceId; }
    public void setData(String data)                       { this.data = data; }
    public void setCreatedAt(Instant createdAt)            { this.createdAt = createdAt; }
}
