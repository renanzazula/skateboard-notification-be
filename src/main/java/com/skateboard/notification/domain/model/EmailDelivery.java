package com.skateboard.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to email one address — the email-channel counterpart of
 * {@link NotificationDelivery}, kept as its own aggregate rather than folded
 * into it: there is no device, no push provider and no receipt-poll step, and
 * forcing those nullable would make every push code path account for rows
 * that can never apply to it.
 *
 * <p>{@code referenceType}/{@code referenceId} mirror {@link Notification}'s
 * fields — "GUEST_APPLICATION" and the application id today, generic enough
 * for a second email-sending feature to reuse this table without a migration.
 */
public class EmailDelivery {

    private final UUID id;
    private final String referenceType;
    private final String referenceId;
    private final String recipientEmail;
    private final String subject;
    private final String body;
    private final EmailProvider provider;
    private EmailDeliveryStatus status;
    private String providerMessageId;
    private int attemptCount;
    private Instant lastAttemptAt;
    private String failureReason;
    private final Instant createdAt;
    private Instant updatedAt;

    private EmailDelivery(UUID id, String referenceType, String referenceId, String recipientEmail,
                          String subject, String body, EmailProvider provider, EmailDeliveryStatus status,
                          String providerMessageId, int attemptCount, Instant lastAttemptAt, String failureReason,
                          Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.recipientEmail = recipientEmail;
        this.subject = subject;
        this.body = body;
        this.provider = provider;
        this.status = status;
        this.providerMessageId = providerMessageId;
        this.attemptCount = attemptCount;
        this.lastAttemptAt = lastAttemptAt;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static EmailDelivery pending(String referenceType, String referenceId, String recipientEmail,
                                        String subject, String body, EmailProvider provider) {
        Instant now = Instant.now();
        return new EmailDelivery(UUID.randomUUID(), referenceType, referenceId, recipientEmail, subject, body,
                provider, EmailDeliveryStatus.PENDING, null, 0, null, null, now, now);
    }

    public static EmailDelivery reconstitute(UUID id, String referenceType, String referenceId,
                                             String recipientEmail, String subject, String body,
                                             EmailProvider provider, EmailDeliveryStatus status,
                                             String providerMessageId, int attemptCount, Instant lastAttemptAt,
                                             String failureReason, Instant createdAt, Instant updatedAt) {
        return new EmailDelivery(id, referenceType, referenceId, recipientEmail, subject, body, provider, status,
                providerMessageId, attemptCount, lastAttemptAt, failureReason, createdAt, updatedAt);
    }

    /** See {@link NotificationDelivery#beginAttempt()} — same reason for counting before the call. */
    public void beginAttempt() {
        this.attemptCount++;
        this.lastAttemptAt = Instant.now();
        this.updatedAt = this.lastAttemptAt;
    }

    /** The provider accepted the message. Not proof it reached an inbox. */
    public void markSent(String providerMessageId) {
        this.status = EmailDeliveryStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.failureReason = null;
        touch();
    }

    /** A transient failure — a timeout, a 5xx, rate limiting. Stays PENDING for the retry pass. */
    public void markRetryable(String failureReason) {
        this.status = EmailDeliveryStatus.PENDING;
        this.failureReason = failureReason;
        touch();
    }

    /** A permanent rejection, or attempts exhausted. No retry will change the answer. */
    public void markFailed(String failureReason) {
        this.status = EmailDeliveryStatus.FAILED;
        this.failureReason = failureReason;
        touch();
    }

    public boolean hasAttemptsLeft(int maxAttempts) {
        return status == EmailDeliveryStatus.PENDING && attemptCount < maxAttempts;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId()                  { return id; }
    public String getReferenceType()     { return referenceType; }
    public String getReferenceId()       { return referenceId; }
    public String getRecipientEmail()    { return recipientEmail; }
    public String getSubject()           { return subject; }
    public String getBody()              { return body; }
    public EmailProvider getProvider()   { return provider; }
    public EmailDeliveryStatus getStatus() { return status; }
    public String getProviderMessageId() { return providerMessageId; }
    public int getAttemptCount()         { return attemptCount; }
    public Instant getLastAttemptAt()    { return lastAttemptAt; }
    public String getFailureReason()     { return failureReason; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }
}
