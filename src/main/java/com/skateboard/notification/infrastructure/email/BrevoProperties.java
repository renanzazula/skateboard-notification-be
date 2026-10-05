package com.skateboard.notification.infrastructure.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Brevo transactional email API client (spec
 * .docs/README_GUEST_APPLICATION_lang.md §9).
 *
 * @param baseUrl          Brevo's host; overridable so tests can point at a
 *                         local MockWebServer instead of the real service
 * @param apiKey           Brevo API key, sent as the {@code api-key} header.
 *                         Never logged, never returned in a response
 * @param senderName       the app/podcast brand, per spec's sender guidance
 * @param senderEmail      e.g. {@code no-reply@your-domain.com}; authenticate
 *                         the sending domain with Brevo per spec §9 before
 *                         relying on this in production
 * @param connectTimeoutMs TCP connect budget
 * @param readTimeoutMs    response budget
 */
@ConfigurationProperties(prefix = "email.brevo")
public record BrevoProperties(String baseUrl,
                               String apiKey,
                               String senderName,
                               String senderEmail,
                               int connectTimeoutMs,
                               int readTimeoutMs) {
}
