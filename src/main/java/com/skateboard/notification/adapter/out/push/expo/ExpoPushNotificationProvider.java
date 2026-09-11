package com.skateboard.notification.adapter.out.push.expo;

import com.skateboard.notification.application.port.out.PushMessage;
import com.skateboard.notification.application.port.out.PushNotificationProviderPort;
import com.skateboard.notification.application.port.out.PushReceipt;
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
    private static final String RECEIPTS_PATH = "/--/api/v2/push/getReceipts";

    /** Expo's documented maximum messages per /push/send request. */
    private static final int MAX_BATCH_SIZE = 100;

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
        this.batchSize = clampBatchSize(properties.batchSize());
    }

    /**
     * A batch size of zero would make the send loop never advance — the
     * listener thread spins forever and the message is never acked — and
     * anything above Expo's documented maximum is rejected outright, which
     * this class would then record as a permanent failure for every message in
     * it. Neither is worth taking the service down for, so a bad value is
     * corrected and reported rather than fatal.
     */
    private int clampBatchSize(int configured) {
        int clamped = Math.clamp(configured, 1, MAX_BATCH_SIZE);
        if (clamped != configured) {
            log.warn("push.expo.batch-size {} is out of range; using {}", configured, clamped);
        }
        return clamped;
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

    @Override
    public List<PushReceipt> fetchReceipts(List<String> providerMessageIds) {
        List<PushReceipt> receipts = new ArrayList<>(providerMessageIds.size());
        for (int start = 0; start < providerMessageIds.size(); start += batchSize) {
            List<String> batch = providerMessageIds.subList(
                    start, Math.min(start + batchSize, providerMessageIds.size()));
            receipts.addAll(fetchReceiptBatch(batch));
        }
        return receipts;
    }

    /**
     * A failure to read receipts is never a delivery failure — nothing has
     * changed about the message, we simply do not know yet. Every id in a batch
     * we could not read comes back NOT_READY so the next pass asks again.
     */
    private List<PushReceipt> fetchReceiptBatch(List<String> ids) {
        try {
            ExpoReceiptResponse response = webClient.post()
                    .uri(RECEIPTS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("ids", ids))
                    .retrieve()
                    .bodyToMono(ExpoReceiptResponse.class)
                    .block();
            return interpretReceipts(ids, response);
        } catch (RuntimeException e) {
            log.warn("Could not read Expo receipts for {} message(s); will ask again", ids.size(), e);
            return ids.stream().map(PushReceipt::notReady).toList();
        }
    }

    private List<PushReceipt> interpretReceipts(List<String> ids, ExpoReceiptResponse response) {
        Map<String, ExpoPushTicket> byId = response == null || response.data() == null
                ? Map.of()
                : response.data();

        List<PushReceipt> receipts = new ArrayList<>(ids.size());
        for (String id : ids) {
            ExpoPushTicket receipt = byId.get(id);
            // Absent means Expo has not produced a receipt yet. Treating that
            // as a failure would retire a delivery that is still in flight.
            if (receipt == null) {
                receipts.add(PushReceipt.notReady(id));
                continue;
            }
            if (ExpoPushTicket.STATUS_OK.equalsIgnoreCase(receipt.status())) {
                receipts.add(PushReceipt.delivered(id));
                continue;
            }
            String errorCode = receipt.errorCode();
            String detail = errorCode == null ? receipt.message() : errorCode + ": " + receipt.message();
            // The failure Expo only ever reports here, never at send time.
            receipts.add(ERROR_DEVICE_NOT_REGISTERED.equals(errorCode)
                    ? PushReceipt.invalidToken(id, detail)
                    : PushReceipt.failed(id, detail));
        }
        return receipts;
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
            // A 2xx that lands here could not be decoded — a proxy error page,
            // an HTML maintenance notice, a truncated body. Spring wraps that
            // failure as a response exception carrying the original status, so
            // without this arm a successful-looking response with an
            // unreadable body would be classified as a permanent rejection and
            // the batch dropped. We do not know what Expo did with it, and
            // "unknown" has to mean retryable.
            if (e.getStatusCode().is2xxSuccessful()) {
                log.warn("Unreadable {} response from Expo; treating the batch as unsent",
                        e.getStatusCode().value(), e);
                return uniform(batch.size(),
                        PushResult.retryable("Unreadable Expo response: " + e.getStatusCode().value()));
            }
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
        } catch (RuntimeException e) {
            // Backstop. A raw CodecException or any other unexpected throw must
            // not escape send() and abandon the batches after this one — that
            // would contradict the promise that a batch which never reached
            // Expo is owed in full.
            log.warn("Unexpected failure talking to Expo; treating the batch as unsent", e);
            return uniform(batch.size(), PushResult.retryable("Expo call failed: " + e.getMessage()));
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
