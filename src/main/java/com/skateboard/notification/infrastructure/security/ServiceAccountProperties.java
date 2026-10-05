package com.skateboard.notification.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for this service's own Keycloak client-credentials grant, used
 * to call skateboard-app-config-be and skateboard-user-be as itself rather
 * than on behalf of a user — there is no user in the loop once an event is
 * on the queue.
 *
 * <p>Requires {@code skateboard-notification-be} to have
 * {@code serviceAccountsEnabled: true} in the realm, with
 * {@code audience-skateboard-app-config-be}/{@code audience-skateboard-user-be}
 * protocol mappers and the service-account user granted
 * {@code FUNC_GUEST_APPLICATION_CONFIGURE}/{@code FUNC_USER_ADMIN_LOOKUP} —
 * none of which this service's own client carried before this feature, since
 * it was previously a resource server only (see application.yml's prior
 * comment on this client).
 *
 * @param tokenUri     the realm's token endpoint, e.g.
 *                     {@code {issuer-uri}/protocol/openid-connect/token}
 * @param clientId     this service's own Keycloak client id
 * @param clientSecret never logged, never returned in a response
 */
@ConfigurationProperties(prefix = "app.security.oauth2.service-account")
public record ServiceAccountProperties(String tokenUri, String clientId, String clientSecret) {
}
