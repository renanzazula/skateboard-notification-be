package com.skateboard.notification.adapter.out.email.brevo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skateboard.notification.application.port.out.EmailMessage;
import com.skateboard.notification.application.port.out.EmailProviderPort;
import com.skateboard.notification.application.port.out.EmailResult;
import com.skateboard.notification.domain.model.EmailProvider;
import com.skateboard.notification.infrastructure.email.BrevoProperties;
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
import java.util.List;
import java.util.Map;

/**
 * The one place in this service that knows Brevo exists — the email
 * counterpart of {@code ExpoPushNotificationProvider}. Everything above it
 * speaks {@link EmailMessage}/{@link EmailResult}, so swapping in Resend or
 * SES later means adding a sibling of this class.
 *
 * <p>Brevo's {@code /v3/smtp/email} endpoint takes one message per call
 * (unlike Expo's batch send), so {@link #send} is a loop; the contract stays
 * batch-shaped so {@link com.skateboard.notification.application.service.DispatchEmailService}
 * doesn't need to know that. Blocking on the WebClient call is deliberate,
 * same reasoning as the Expo adapter: this runs on a RabbitMQ listener
 * thread or a retry-job thread, not a reactive pipeline.
 */
@Component
public class BrevoEmailProvider implements EmailProviderPort {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailProvider.class);

    private static final String SEND_PATH = "/v3/smtp/email";

    private final WebClient webClient;
    private final String senderName;
    private final String senderEmail;

    public BrevoEmailProvider(WebClient.Builder webClientBuilder, BrevoProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.readTimeoutMs()));

        this.webClient = webClientBuilder
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("api-key", properties.apiKey())
                .build();
        this.senderName = properties.senderName();
        this.senderEmail = properties.senderEmail();
    }

    @Override
    public EmailProvider provider() {
        return EmailProvider.BREVO;
    }

    @Override
    public List<EmailResult> send(List<EmailMessage> messages) {
        return messages.stream().map(this::sendOne).toList();
    }

    private EmailResult sendOne(EmailMessage message) {
        try {
            BrevoSendResponse response = webClient.post()
                    .uri(SEND_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toBrevoPayload(message))
                    .retrieve()
                    .bodyToMono(BrevoSendResponse.class)
                    .block();
            if (response == null || response.messageId() == null || response.messageId().isBlank()) {
                log.warn("Brevo accepted the request but returned no messageId; treating as unsent");
                return EmailResult.retryable("Brevo returned no messageId");
            }
            return EmailResult.accepted(response.messageId());
        } catch (WebClientResponseException e) {
            // A 2xx that lands here could not be decoded — same reasoning as
            // the Expo adapter's identical arm: "unknown" has to mean retryable.
            if (e.getStatusCode().is2xxSuccessful()) {
                log.warn("Unreadable {} response from Brevo; treating the email as unsent", e.getStatusCode().value(), e);
                return EmailResult.retryable("Unreadable Brevo response: " + e.getStatusCode().value());
            }
            boolean retryable = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429;
            String detail = "Brevo responded " + e.getStatusCode().value();
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                log.error("Brevo rejected the API key (status {}); every email will fail until this is fixed",
                        e.getStatusCode().value());
            }
            return retryable ? EmailResult.retryable(detail) : EmailResult.rejected(detail);
        } catch (WebClientRequestException e) {
            // Never reached Brevo — a timeout or a connection failure.
            return EmailResult.retryable("Brevo unreachable: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Unexpected failure talking to Brevo; treating the email as unsent", e);
            return EmailResult.retryable("Brevo call failed: " + e.getMessage());
        }
    }

    private Map<String, Object> toBrevoPayload(EmailMessage message) {
        return Map.of(
                "sender", Map.of("name", senderName, "email", senderEmail),
                "to", List.of(Map.of("email", message.to())),
                "subject", message.subject(),
                "textContent", message.body());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BrevoSendResponse(String messageId) {
    }
}
