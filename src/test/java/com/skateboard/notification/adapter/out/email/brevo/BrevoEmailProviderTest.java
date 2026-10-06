package com.skateboard.notification.adapter.out.email.brevo;

import com.skateboard.notification.application.port.out.EmailMessage;
import com.skateboard.notification.application.port.out.EmailResult;
import com.skateboard.notification.infrastructure.email.BrevoProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the provider against a real HTTP server on loopback, mirroring
 * {@code ExpoPushNotificationProviderTest} — it is the response parsing and
 * the failure classification that are worth testing.
 */
class BrevoEmailProviderTest {

    private MockWebServer server;
    private BrevoEmailProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new BrevoEmailProvider(WebClient.builder(),
                new BrevoProperties(server.url("/").toString().replaceAll("/$", ""), "test-api-key",
                        "Skateboard Podcast", "no-reply@example.test", 2000, 5000));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void anAcceptedEmailCarriesTheMessageId() {
        server.enqueue(new MockResponse().setResponseCode(201)
                .setBody("{\"messageId\":\"msg-1\"}")
                .addHeader("Content-Type", "application/json"));

        List<EmailResult> results = provider.send(List.of(message()));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo(EmailResult.Outcome.ACCEPTED);
            assertThat(result.providerMessageId()).isEqualTo("msg-1");
        });
    }

    @Test
    void sendsTheApiKeyHeaderAndTheExpectedPayload() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"messageId\":\"msg-1\"}")
                .addHeader("Content-Type", "application/json"));

        provider.send(List.of(new EmailMessage("jane@example.com", "Hi", "Body text")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("api-key")).isEqualTo("test-api-key");
        assertThat(request.getPath()).isEqualTo("/v3/smtp/email");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"jane@example.com\"").contains("\"Hi\"").contains("\"Body text\"")
                .contains("no-reply@example.test");
    }

    @Test
    void aResponseWithNoMessageIdIsRetryable() {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{}")
                .addHeader("Content-Type", "application/json"));

        assertThat(provider.send(List.of(message())))
                .singleElement()
                .extracting(EmailResult::outcome)
                .isEqualTo(EmailResult.Outcome.RETRYABLE);
    }

    @Test
    void aBadRequestIsRejected() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"message\":\"invalid email\"}")
                .addHeader("Content-Type", "application/json"));

        assertThat(provider.send(List.of(message())))
                .singleElement()
                .extracting(EmailResult::outcome)
                .isEqualTo(EmailResult.Outcome.REJECTED);
    }

    @Test
    void anUnauthorizedResponseIsRejected() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\":\"bad key\"}")
                .addHeader("Content-Type", "application/json"));

        assertThat(provider.send(List.of(message())))
                .singleElement()
                .extracting(EmailResult::outcome)
                .isEqualTo(EmailResult.Outcome.REJECTED);
    }

    @Test
    void rateLimitingIsRetryable() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"message\":\"slow down\"}")
                .addHeader("Content-Type", "application/json"));

        assertThat(provider.send(List.of(message())))
                .singleElement()
                .extracting(EmailResult::outcome)
                .isEqualTo(EmailResult.Outcome.RETRYABLE);
    }

    @Test
    void aServerErrorIsRetryable() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{}")
                .addHeader("Content-Type", "application/json"));

        assertThat(provider.send(List.of(message())))
                .singleElement()
                .extracting(EmailResult::outcome)
                .isEqualTo(EmailResult.Outcome.RETRYABLE);
    }

    @Test
    void anUnreachableServerIsRetryable() throws Exception {
        server.shutdown();

        assertThat(provider.send(List.of(message())))
                .singleElement()
                .extracting(EmailResult::outcome)
                .isEqualTo(EmailResult.Outcome.RETRYABLE);
    }

    @Test
    void sendsOneMessagePerCallAndReturnsResultsInOrder() {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"messageId\":\"msg-1\"}")
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{}")
                .addHeader("Content-Type", "application/json"));

        List<EmailResult> results = provider.send(List.of(
                new EmailMessage("a@example.com", "A", "a"),
                new EmailMessage("b@example.com", "B", "b")));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).outcome()).isEqualTo(EmailResult.Outcome.ACCEPTED);
        assertThat(results.get(1).outcome()).isEqualTo(EmailResult.Outcome.REJECTED);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    private EmailMessage message() {
        return new EmailMessage("jane@example.com", "We've received your application", "Thanks for applying!");
    }
}
