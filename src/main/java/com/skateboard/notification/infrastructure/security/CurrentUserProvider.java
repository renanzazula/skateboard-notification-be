package com.skateboard.notification.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Reads the caller out of the security context. Both REST controllers need the
 * same pair of values, and the tenant claim needs a fallback, so this lives in
 * one place rather than being repeated as a private helper per controller the
 * way skateboard-podcast-be does it for user id alone.
 *
 * <p>Unlike {@code PodcastController#resolveCurrentUserId}, a failure here
 * throws rather than returning null: every endpoint on this service is
 * authenticated and scoped to the caller, so an unresolvable subject is a bug
 * or an attack, never a legitimate anonymous read.
 */
@Component
public class CurrentUserProvider {

    private static final String TENANT_CLAIM = "tenant_id";

    private final UUID defaultTenantId;

    public CurrentUserProvider(@Value("${app.tenancy.default-tenant-id}") UUID defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public CurrentUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException("No authenticated JWT in the security context");
        }
        Jwt jwt = jwtAuth.getToken();
        return new CurrentUser(parseSubject(jwt.getSubject()), resolveTenantId(jwt));
    }

    private UUID parseSubject(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("JWT subject is not a Keycloak user id");
        }
    }

    /**
     * tenant_id is an admin-managed Keycloak user attribute, backfilled lazily
     * by skateboard-user-be, so identities that predate that backfill still
     * carry no claim. Falling back keeps them notifiable instead of dropping
     * them silently — with one tenant in existence, the fallback is also the
     * only correct answer.
     */
    private UUID resolveTenantId(Jwt jwt) {
        String claim = jwt.getClaimAsString(TENANT_CLAIM);
        if (claim == null || claim.isBlank()) {
            return defaultTenantId;
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            return defaultTenantId;
        }
    }
}
