package com.skateboard.notification.infrastructure.security;

import java.util.UUID;

/**
 * The caller's identity as this service is willing to trust it: both values
 * come from the validated JWT and never from a request body or path, which is
 * the whole point — a client must not be able to register a device against
 * another user or reach another tenant's recipients (spec §10, §28).
 *
 * @param id       the JWT's "sub" — Keycloak populates it with the user's UUID
 * @param tenantId the JWT's "tenant_id" claim, or the configured default when
 *                 the token carries none
 */
public record CurrentUser(UUID id, UUID tenantId) {
}
