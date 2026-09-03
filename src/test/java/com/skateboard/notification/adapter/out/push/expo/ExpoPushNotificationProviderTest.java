package com.skateboard.notification.adapter.out.push.expo;

import com.skateboard.notification.application.port.out.PushMessage;
import com.skateboard.notification.application.port.out.PushResult;
import com.skateboard.notification.infrastructure.push.ExpoProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the provider against a real HTTP server on loopback rather than a
 * mocked WebClient, matching how skateboard-podcast-be tests its YouTube and
 * Spotify clients — it is the response parsing and the failure classification
 * that are worth testing, and mocking the client would skip both.
 *
 * <p>The classification is the point. Expo answers 200 with a per-message
 * ticket array, so an HTTP success can still contain a dead token, and getting
 * that wrong either burns attempts forever or drops notifications silently.
 */
class ExpoPushNotificationProviderTest {

    private MockWebServer server;
    private ExpoPushNotificationProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new ExpoPushNotificationProvider(WebClient.builder(),
                new ExpoProperties(server.url("/").toString().replaceAll("/$", ""), null, 2000, 5000, 100));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void anOkTicketIsAcceptedAndCarriesTheReceiptId() {
        enqueue(200, "{\"data\":[{\"status\":\"ok\",\"id\":\"receipt-1\"}]}");

        List<PushResult> results = provider.send(List.of(message("ExponentPushToken[a]")));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.outcome()).isEqualTo(PushResult.Outcome.ACCEPTED);
            assertThat(result.providerMessageId()).isEqualTo("receipt-1");
        });
    }

    @Test
    void deviceNotRegisteredIsReportedAsAnInvalidTokenSoTheDeviceCanBeDisabled() {
        enqueue(200, """
                {"data":[{"status":"error","message":"not registered",
                          "details":{"error":"DeviceNotRegistered"}}]}""");

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"))))
                .singleElement()
                .extracting(PushResult::outcome)
                .isEqualTo(PushResult.Outcome.INVALID_TOKEN);
    }

    @Test
    void rateLimitingIsRetryableBecauseTheTokenItselfIsFine() {
        enqueue(200, """
                {"data":[{"status":"error","message":"slow down",
                          "details":{"error":"MessageRateExceeded"}}]}""");

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"))))
                .singleElement()
                .extracting(PushResult::outcome)
                .isEqualTo(PushResult.Outcome.RETRYABLE);
    }

    @Test
    void anUnrecognisedTicketErrorIsRejectedRatherThanRetriedForever() {
        enqueue(200, """
                {"data":[{"status":"error","message":"too big",
                          "details":{"error":"MessageTooBig"}}]}""");

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"))))
                .singleElement()
                .extracting(PushResult::outcome)
                .isEqualTo(PushResult.Outcome.REJECTED);
    }

    @Test
    void anExpoOutageIsRetryableForEveryMessageInTheBatch() {
        enqueue(503, "service unavailable");

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"), message("ExponentPushToken[b]"))))
                .hasSize(2)
                .allSatisfy(result ->
                        assertThat(result.outcome()).isEqualTo(PushResult.Outcome.RETRYABLE));
    }

    @Test
    void aRequestExpoRejectedOutrightIsNotRetried() {
        enqueue(400, "bad request");

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"))))
                .singleElement()
                .extracting(PushResult::outcome)
                .isEqualTo(PushResult.Outcome.REJECTED);
    }

    /**
     * Expo told us nothing about the second message, so claiming it was sent
     * would lose it.
     */
    @Test
    void messagesExpoReturnedNoTicketForAreRetryable() {
        enqueue(200, "{\"data\":[{\"status\":\"ok\",\"id\":\"receipt-1\"}]}");

        List<PushResult> results =
                provider.send(List.of(message("ExponentPushToken[a]"), message("ExponentPushToken[b]")));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).outcome()).isEqualTo(PushResult.Outcome.ACCEPTED);
        assertThat(results.get(1).outcome()).isEqualTo(PushResult.Outcome.RETRYABLE);
    }

    @Test
    void aResponseWithNoTicketArrayAtAllIsRetryable() {
        enqueue(200, "{}");

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"))))
                .singleElement()
                .extracting(PushResult::outcome)
                .isEqualTo(PushResult.Outcome.RETRYABLE);
    }

    /**
     * A 200 carrying something that is not JSON — a proxy error page, an HTML
     * maintenance notice — throws a CodecException, which is neither of
     * WebClient's own exception types. Unhandled it would escape send()
     * entirely and abandon every remaining batch.
     */
    @Test
    void anUnreadableBodyOnA200IsRetryableRatherThanEscapingTheSend() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>502 Bad Gateway</body></html>"));

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"))))
                .singleElement()
                .extracting(PushResult::outcome)
                .isEqualTo(PushResult.Outcome.RETRYABLE);
    }

    /** A later batch must still go out after an earlier one came back unreadable. */
    @Test
    void keepsSendingLaterBatchesAfterAnUnreadableResponse() {
        provider = new ExpoPushNotificationProvider(WebClient.builder(),
                new ExpoProperties(baseUrl(), null, 2000, 5000, 1));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html").setBody("<html>nope</html>"));
        enqueue(200, "{\"data\":[{\"status\":\"ok\",\"id\":\"receipt-2\"}]}");

        List<PushResult> results =
                provider.send(List.of(message("ExponentPushToken[a]"), message("ExponentPushToken[b]")));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).outcome()).isEqualTo(PushResult.Outcome.RETRYABLE);
        assertThat(results.get(1).outcome()).isEqualTo(PushResult.Outcome.ACCEPTED);
    }

    /**
     * Zero would make the send loop never advance — the listener thread spins
     * forever and the message is never acked.
     */
    @Test
    void aBatchSizeOfZeroIsCorrectedRatherThanHangingTheSendLoop() {
        provider = new ExpoPushNotificationProvider(WebClient.builder(),
                new ExpoProperties(baseUrl(), null, 2000, 5000, 0));
        enqueue(200, "{\"data\":[{\"status\":\"ok\",\"id\":\"receipt-1\"}]}");

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"))))
                .singleElement()
                .extracting(PushResult::outcome)
                .isEqualTo(PushResult.Outcome.ACCEPTED);
    }

    @Test
    void aBatchSizeAboveExposMaximumIsCappedSoTheRequestIsNotRejected() {
        provider = new ExpoPushNotificationProvider(WebClient.builder(),
                new ExpoProperties(baseUrl(), null, 2000, 5000, 5000));
        enqueue(200, "{\"data\":[{\"status\":\"ok\",\"id\":\"receipt-1\"}]}");

        assertThat(provider.send(List.of(message("ExponentPushToken[a]"))))
                .singleElement()
                .extracting(PushResult::outcome)
                .isEqualTo(PushResult.Outcome.ACCEPTED);
    }

    @Test
    void splitsMessagesIntoBatchesOfTheConfiguredSize() throws Exception {
        provider = new ExpoPushNotificationProvider(WebClient.builder(),
                new ExpoProperties(server.url("/").toString().replaceAll("/$", ""), null, 2000, 5000, 2));
        enqueue(200, "{\"data\":[{\"status\":\"ok\",\"id\":\"r1\"},{\"status\":\"ok\",\"id\":\"r2\"}]}");
        enqueue(200, "{\"data\":[{\"status\":\"ok\",\"id\":\"r3\"}]}");

        List<PushResult> results = provider.send(List.of(
                message("ExponentPushToken[a]"), message("ExponentPushToken[b]"), message("ExponentPushToken[c]")));

        assertThat(results).hasSize(3)
                .allSatisfy(result -> assertThat(result.outcome()).isEqualTo(PushResult.Outcome.ACCEPTED));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void sendsTheDeepLinkMetadataExpoWillHandBackToTheApp() throws Exception {
        enqueue(200, "{\"data\":[{\"status\":\"ok\",\"id\":\"receipt-1\"}]}");

        provider.send(List.of(message("ExponentPushToken[a]")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/--/api/v2/push/send");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"to\":\"ExponentPushToken[a]\"")
                .contains("\"targetType\":\"PODCAST\"");
    }

    private String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private PushMessage message(String token) {
        return new PushMessage(token, "New podcast available", "Barcelona Street Sessions #14",
                Map.of("targetType", "PODCAST", "targetSlug", "barcelona-street-sessions-14"));
    }
}
