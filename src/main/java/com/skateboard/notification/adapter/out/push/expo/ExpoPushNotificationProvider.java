package com.skateboard.notification.adapter.out.push.expo;

import com.skateboard.notification.application.port.out.PushMessage;
import com.skateboard.notification.application.port.out.PushNotificationProviderPort;
import com.skateboard.notification.application.port.out.PushResult;
import com.skateboard.notification.domain.model.PushProvider;
import com.skateboard.notification.infrastructure.push.ExpoProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place in this service that knows Expo exists.
 *
 * <p>Everything above it speaks {@link PushMessage} / {@link PushResult}, so
 * adding FCM or APNs later means adding a sibling of this class (spec Decision
 * 4). Blocking on the WebClient call is deliberate: this runs on a RabbitMQ
 * listener thread, not in a reactive pipeline, and going async here would buy
 * nothing while complicating transaction and MDC handling.
 *
 * <p>The failure taxonomy is the part worth getting right. Expo answers 200
 * with a per-message ticket array, so a "successful" HTTP call can still
 * contain dead tokens; and a batch that fails at the transport level has no
 * tickets at all, in which case every message in it is retryable rather than
 * lost.
 */
@Component
public class ExpoPushNotificationProvider implements PushNotificationProviderPort {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushNotificationProvider.class);

    private static final String SEND_PATH = "/--/api/v2/push/send";

    /** Expo's code for a token that will never work again. */
    private static final String ERROR_DEVICE_NOT_REGISTERED = "DeviceNotRegistered";
    /** Back off and try later; the token itself is fine. */
    private static final String ERROR_RATE_EXCEEDED = "MessageRateExceeded";

    private final WebClient webClient;
    private final int batchSize;

    public ExpoPushNotificationProvider(WebClient.Builder webClientBuilder, ExpoProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        WebClient.Builder builder = webClientBuilder
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        if (properties.accessToken() != null && !properties.accessToken().isBlank()) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken());
        }

        this.webClient = builder.build();
        this.batchSize = properties.batchSize();
    }

    @Override
    public PushProvider provider() {
        return PushProvider.EXPO;
    }

    @Override
    public List<PushResult> send(List<PushMessage> messages) {
        List<PushResult> results = new ArrayList<>(messages.size());
        for (int start = 0; start < messages.size(); start += batchSize) {
            List<PushMessage> batch = messages.subList(start, Math.min(start + batchSize, messages.size()));
            results.addAll(sendBatch(batch));
        }
        return results;
    }

    private List<PushResult> sendBatch(List<PushMessage> batch) {
        try {
            ExpoPushResponse response = webClient.post()
                    .uri(SEND_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(batch.stream().map(this::toExpoPayload).toList())
                    .retrieve()
                    .bodyToMono(ExpoPushResponse.class)
                    .block();
            return interpret(batch, response);
        } catch (WebClientResponseException e) {
            // 429 and 5xx are Expo's problem and will likely clear; a 4xx we
            // caused will not, but the delivery row records the reason either
            // way and a bounded retry is cheap next to a lost notification.
            boolean retryable = e.getStatusCode().is5xxServerError()
                    || e.getStatusCode().value() == 429;
            String detail = "Expo responded " + e.getStatusCode().value();
            return uniform(batch.size(), retryable ? PushResult.retryable(detail) : PushResult.rejected(detail));
        } catch (WebClientRequestException e) {
            // Never reached Expo — a timeout or a connection failure. Nothing
            // was sent, so every message in the batch is still owed.
            return uniform(batch.size(), PushResult.retryable("Expo unreachable: " + e.getMessage()));
        }
    }

    /**
     * Expo returns one ticket per message, in order. A response that is absent
     * or short-changes us is treated as retryable for the messages it did not
     * cover: assuming success there would silently drop notifications.
     */
    private List<PushResult> interpret(List<PushMessage> batch, ExpoPushResponse response) {
        List<ExpoPushTicket> tickets = response == null ? null : response.data();
        if (tickets == null) {
            return uniform(batch.size(), PushResult.retryable("Expo returned no tickets"));
        }

        List<PushResult> results = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            if (i >= tickets.size()) {
                results.add(PushResult.retryable("Expo returned fewer tickets than messages"));
                continue;
            }
            results.add(toResult(tickets.get(i)));
        }
        return results;
    }

    private PushResult toResult(ExpoPushTicket ticket) {
        if (ExpoPushTicket.STATUS_OK.equalsIgnoreCase(ticket.status())) {
            return PushResult.accepted(ticket.id());
        }
        String errorCode = ticket.errorCode();
        String detail = errorCode == null ? ticket.message() : errorCode + ": " + ticket.message();
        if (ERROR_DEVICE_NOT_REGISTERED.equals(errorCode)) {
            return PushResult.invalidToken(detail);
        }
        if (ERROR_RATE_EXCEEDED.equals(errorCode)) {
            return PushResult.retryable(detail);
        }
        log.warn("Expo rejected a push: {}", detail);
        return PushResult.rejected(detail);
    }

    private List<PushResult> uniform(int size, PushResult result) {
        List<PushResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            results.add(result);
        }
        return results;
    }

    private Map<String, Object> toExpoPayload(PushMessage message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("to", message.pushToken());
        payload.put("title", message.title());
        payload.put("body", message.body());
        payload.put("data", message.data());
        // Wakes the handset rather than queueing quietly, and gives Android a
        // channel that exists (see the expo-notifications plugin config).
        payload.put("priority", "high");
        payload.put("channelId", "default");
        payload.put("sound", "default");
        return payload;
    }
}
