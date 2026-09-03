package com.skateboard.notification.infrastructure.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Expo Push API client.
 *
 * @param baseUrl          Expo's host; overridable so tests can point at a
 *                         local MockWebServer instead of the real service
 * @param accessToken      optional; only required once Expo's enhanced push
 *                         security is enabled for the project. Never logged
 * @param connectTimeoutMs TCP connect budget
 * @param readTimeoutMs    response budget — generous, because a full batch of
 *                         100 goes through APNs and FCM before Expo answers
 * @param batchSize        messages per /push/send call; 100 is Expo's maximum
 */
@ConfigurationProperties(prefix = "push.expo")
public record ExpoProperties(String baseUrl,
                              String accessToken,
                              int connectTimeoutMs,
                              int readTimeoutMs,
                              int batchSize) {
}
