package com.skateboard.notification.infrastructure.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where to reach skateboard-user-be for admin identity lookups. */
@ConfigurationProperties(prefix = "clients.user-be")
public record UserDirectoryClientProperties(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
}
