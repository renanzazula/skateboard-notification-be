package com.skateboard.notification.adapter.out.directory.appconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skateboard.notification.application.port.out.GuestApplicationSettingsPort;
import com.skateboard.notification.infrastructure.directory.AppConfigClientProperties;
import com.skateboard.notification.infrastructure.security.ServiceAccountTokenProvider;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Calls skateboard-app-config-be's {@code GET /api/guest-application-settings/admin}
 * as this service's own client-credentials identity (spec: recipients and
 * the confirmation template are read live, never snapshotted — see
 * {@link GuestApplicationSettingsPort}).
 */
@Component
public class AppConfigGuestApplicationSettingsClient implements GuestApplicationSettingsPort {

    private static final Logger log = LoggerFactory.getLogger(AppConfigGuestApplicationSettingsClient.class);

    private static final String PATH = "/api/guest-application-settings/admin";

    private final WebClient webClient;
    private final ServiceAccountTokenProvider tokenProvider;

    public AppConfigGuestApplicationSettingsClient(WebClient.Builder webClientBuilder,
                                                   AppConfigClientProperties properties,
                                                   ServiceAccountTokenProvider tokenProvider) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        this.webClient = webClientBuilder
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.tokenProvider = tokenProvider;
    }

    @Override
    public Optional<Settings> getSettings() {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            log.error("No service-account token available; cannot read Guest Application settings");
            return Optional.empty();
        }
        try {
            Response response = webClient.get()
                    .uri(PATH)
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(Response.class)
                    .block();
            if (response == null) {
                return Optional.empty();
            }
            return Optional.of(new Settings(response.enabled(),
                    response.recipientIds() == null ? List.of() : response.recipientIds(),
                    response.confirmationSubject(), response.confirmationBody()));
        } catch (RuntimeException e) {
            log.error("Could not read Guest Application settings from app-config-be: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(boolean enabled, List<UUID> recipientIds, String confirmationSubject,
                            String confirmationBody) {
    }
}
