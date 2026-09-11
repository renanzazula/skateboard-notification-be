package com.skateboard.notification.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_event")
public class ProcessedEventJpaEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEventJpaEntity() {
        // required by JPA
    }

    public UUID getEventId()        { return eventId; }
    public String getEventType()    { return eventType; }
    public Instant getProcessedAt() { return processedAt; }

    public void setEventId(UUID eventId)            { this.eventId = eventId; }
    public void setEventType(String eventType)      { this.eventType = eventType; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
