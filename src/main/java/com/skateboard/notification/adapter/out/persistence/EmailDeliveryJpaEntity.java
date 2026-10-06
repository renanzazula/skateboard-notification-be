package com.skateboard.notification.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_delivery")
public class EmailDeliveryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "reference_type")
    private String referenceType;

    @Column(name = "reference_id")
    private String referenceId;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

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

    public EmailDeliveryJpaEntity() {
        // required by JPA
    }

    public UUID getId()                  { return id; }
    public String getReferenceType()     { return referenceType; }
    public String getReferenceId()       { return referenceId; }
    public String getRecipientEmail()    { return recipientEmail; }
    public String getSubject()           { return subject; }
    public String getBody()              { return body; }
    public String getProvider()          { return provider; }
    public String getStatus()            { return status; }
    public String getProviderMessageId() { return providerMessageId; }
    public int getAttemptCount()         { return attemptCount; }
    public Instant getLastAttemptAt()    { return lastAttemptAt; }
    public String getFailureReason()     { return failureReason; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }

    public void setId(UUID id)                                 { this.id = id; }
    public void setReferenceType(String referenceType)         { this.referenceType = referenceType; }
    public void setReferenceId(String referenceId)             { this.referenceId = referenceId; }
    public void setRecipientEmail(String recipientEmail)       { this.recipientEmail = recipientEmail; }
    public void setSubject(String subject)                     { this.subject = subject; }
    public void setBody(String body)                           { this.body = body; }
    public void setProvider(String provider)                   { this.provider = provider; }
    public void setStatus(String status)                       { this.status = status; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
    public void setAttemptCount(int attemptCount)               { this.attemptCount = attemptCount; }
    public void setLastAttemptAt(Instant lastAttemptAt)         { this.lastAttemptAt = lastAttemptAt; }
    public void setFailureReason(String failureReason)          { this.failureReason = failureReason; }
    public void setCreatedAt(Instant createdAt)                 { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt)                 { this.updatedAt = updatedAt; }
}
