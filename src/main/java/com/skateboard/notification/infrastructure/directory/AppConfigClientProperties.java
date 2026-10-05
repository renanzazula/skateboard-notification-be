package com.skateboard.notification.infrastructure.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where to reach skateboard-app-config-be for Guest Application settings. */
@ConfigurationProperties(prefix = "clients.app-config-be")
public record AppConfigClientProperties(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
}
