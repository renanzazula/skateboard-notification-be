package com.skateboard.notification.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 resource server validating access tokens issued by Keycloak: RS256,
 * verified against the realm's JWKS endpoint, restricted to tokens whose
 * "aud" includes this service (see {@link AudienceValidator}).
 * <p>
 * Every /me operation is scoped to the caller by JWT subject (see
 * UserController.currentUserId()) and additionally requires an
 * operation-specific FUNC_USER_* authority via @PreAuthorize (see
 * UserController and api/openapi.yaml's x-required-permissions) — granted to
 * both the ADMIN and STANDARD realm roles, not GUEST, in the shared
 * realm-export, the same FUNC_*-authority convention skateboard-podcast-be's
 * controllers use. @EnableMethodSecurity is what makes those @PreAuthorize
 * checks active.
 * <p>
 * The JWKS URI is built directly from {@code issuerUri} (Keycloak's stable
 * {@code /protocol/openid-connect/certs} convention) instead of doing OIDC
 * discovery, keeping key fetching lazy so app startup doesn't couple to
 * Keycloak being reachable at that exact moment.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final String issuerUri;
    private final String requiredAudience;

    public SecurityConfig(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
                           @Value("${app.security.oauth2.audience}") String requiredAudience) {
        this.issuerUri = issuerUri;
        this.requiredAudience = requiredAudience;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // health for the platform's liveness probe; prometheus
                        // so a scraper can be pointed here without a token dance.
                        // Both expose counts and status only — no push tokens,
                        // no PII (see NotificationMetrics).
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt
                        .decoder(jwtDecoder())
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    private JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuerUri + "/protocol/openid-connect/certs").build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(requiredAudience));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("authorities");
        authoritiesConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
