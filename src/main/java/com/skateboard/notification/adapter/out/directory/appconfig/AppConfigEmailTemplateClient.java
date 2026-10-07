package com.skateboard.notification.adapter.out.directory.appconfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skateboard.notification.application.port.out.EmailTemplateResolverPort;
import com.skateboard.notification.domain.model.EmailTemplateType;
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
import java.util.Optional;

/**
 * Calls skateboard-app-config-be's {@code GET /api/email-templates/{type}/{language}}
 * as this service's own client-credentials identity — same token/properties
 * as {@link AppConfigGuestApplicationSettingsClient}, just a different path.
 */
@Component
public class AppConfigEmailTemplateClient implements EmailTemplateResolverPort {

    private static final Logger log = LoggerFactory.getLogger(AppConfigEmailTemplateClient.class);

    private final WebClient webClient;
    private final ServiceAccountTokenProvider tokenProvider;

    public AppConfigEmailTemplateClient(WebClient.Builder webClientBuilder,
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
    public Optional<Template> resolve(EmailTemplateType type, String language) {
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            log.error("No service-account token available; cannot read email template {}/{}", type, language);
            return Optional.empty();
        }
        try {
            Response response = webClient.get()
                    .uri("/api/email-templates/{type}/{language}", type, language)
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(Response.class)
                    .block();
            if (response == null) {
                return Optional.empty();
            }
            return Optional.of(new Template(response.subject(), response.body(), response.enabled()));
        } catch (RuntimeException e) {
            log.error("Could not read email template {}/{} from app-config-be: {}", type, language, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(String subject, String body, boolean enabled) {
    }
}
