package com.skateboard.notification.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;

/**
 * Fetches and caches this service's own client-credentials access token
 * (spec: notification-be calling app-config-be/user-be as itself — see
 * {@link ServiceAccountProperties}).
 *
 * <p>A single shared cache rather than one per caller: both
 * {@code AppConfigGuestApplicationSettingsClient} and {@code UserDirectoryClient}
 * authenticate as the same client and the token carries both services'
 * audiences (two {@code oidc-audience-mapper}s on one client), so there is
 * exactly one token to fetch and reuse. Refreshed a safety margin before
 * expiry rather than on 401, since every caller here runs on a
 * listener/retry-job thread where a surprise re-auth round trip is wasted
 * latency, not a correctness problem.
 */
@Component
public class ServiceAccountTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceAccountTokenProvider.class);

    /** Refresh this long before the token's stated expiry, to absorb clock skew and request latency. */
    private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(30);

    private final WebClient tokenClient;
    private final ServiceAccountProperties properties;
    private volatile CachedToken cached;

    public ServiceAccountTokenProvider(WebClient.Builder webClientBuilder, ServiceAccountProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(5));
        this.tokenClient = webClientBuilder
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();
        this.properties = properties;
    }

    /** @return a currently-valid bearer token, or null if one could not be obtained */
    public synchronized String getAccessToken() {
        if (cached == null || cached.isExpiringBy(Instant.now())) {
            cached = fetchToken();
        }
        return cached == null ? null : cached.accessToken();
    }

    private CachedToken fetchToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        try {
            TokenResponse response = tokenClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(TokenResponse.class)
                    .block();
            if (response == null || response.accessToken() == null) {
                log.error("Keycloak token endpoint returned no access token");
                return null;
            }
            Instant expiresAt = Instant.now().plusSeconds(Math.max(response.expiresIn(), 0));
            return new CachedToken(response.accessToken(), expiresAt);
        } catch (RuntimeException e) {
            log.error("Could not obtain a service-account token from {}: {}",
                    properties.tokenUri(), e.getMessage(), e);
            return null;
        }
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        boolean isExpiringBy(Instant now) {
            return !now.plus(EXPIRY_SAFETY_MARGIN).isBefore(expiresAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(String accessToken, long expiresIn) {
        @com.fasterxml.jackson.annotation.JsonCreator
        TokenResponse(@com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
                      @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn) {
            this.accessToken = accessToken;
            this.expiresIn = expiresIn;
        }
    }
}
