package com.skateboard.notification.adapter.in.messaging.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * The metadata every business event on the platform carries (spec §6),
 * with the type-specific part left as a raw tree so one listener can validate
 * the envelope before anything knows what the payload means.
 *
 * @param eventId    unique per event, and stable across re-emissions of the
 *                   same fact — this is what makes idempotency possible
 * @param eventType  discriminates the payload
 * @param version    payload schema version; also encoded in the routing key
 * @param tenantId   the tenant the event happened in
 * @param occurredAt when it happened, not when it was delivered
 * @param payload    type-specific body
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DomainEventEnvelope<T>(UUID eventId,
                                      String eventType,
                                      Integer version,
                                      UUID tenantId,
                                      Instant occurredAt,
                                      T payload) {
}
