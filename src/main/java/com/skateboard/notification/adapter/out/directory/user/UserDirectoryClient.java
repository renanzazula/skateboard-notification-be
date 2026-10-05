package com.skateboard.notification.adapter.out.directory.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skateboard.notification.application.port.out.RecipientDirectoryPort;
import com.skateboard.notification.infrastructure.directory.UserDirectoryClientProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Calls skateboard-user-be's {@code GET /admin/users/lookup} as this
 * service's own client-credentials identity, to resolve Guest Application
 * recipient ids to verified, active email addresses (spec §9, §6).
 */
@Component
public class UserDirectoryClient implements RecipientDirectoryPort {

    private static final Logger log = LoggerFactory.getLogger(UserDirectoryClient.class);

    private static final String PATH = "/admin/users/lookup";
    /** The endpoint's own documented maximum per call. */
    private static final int MAX_BATCH_SIZE = 100;

    private final WebClient webClient;
    private final ServiceAccountTokenProvider tokenProvider;

    public UserDirectoryClient(WebClient.Builder webClientBuilder, UserDirectoryClientProperties properties,
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
    public List<ResolvedRecipient> resolve(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        String token = tokenProvider.getAccessToken();
        if (token == null) {
            log.error("No service-account token available; cannot resolve {} recipient(s)", userIds.size());
            return List.of();
        }

        List<ResolvedRecipient> resolved = new ArrayList<>();
        for (int start = 0; start < userIds.size(); start += MAX_BATCH_SIZE) {
            List<UUID> batch = userIds.subList(start, Math.min(start + MAX_BATCH_SIZE, userIds.size()));
            resolved.addAll(resolveBatch(batch, token));
        }
        return resolved;
    }

    private List<ResolvedRecipient> resolveBatch(List<UUID> ids, String token) {
        try {
            UserLookupResponse[] response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path(PATH).queryParam("ids", ids).build())
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(UserLookupResponse[].class)
                    .block();
            if (response == null) {
                return List.of();
            }
            return List.of(response).stream()
                    .map(r -> new ResolvedRecipient(r.id(), r.email(), r.emailVerified(), r.active()))
                    .toList();
        } catch (RuntimeException e) {
            log.error("Could not resolve {} recipient(s) from user-be: {}", ids.size(), e.getMessage(), e);
            return List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserLookupResponse(UUID id, String email, boolean emailVerified, boolean active) {
    }
}
