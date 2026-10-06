package com.skateboard.notification.infrastructure.security;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountTokenProviderTest {

    private MockWebServer server;
    private ServiceAccountTokenProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new ServiceAccountTokenProvider(WebClient.builder(),
                new ServiceAccountProperties(server.url("/token").toString(), "skateboard-notification-be", "secret"));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void fetchesATokenUsingTheClientCredentialsGrant() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"access_token\":\"token-1\",\"expires_in\":300}")
                .addHeader("Content-Type", "application/json"));

        assertThat(provider.getAccessToken()).isEqualTo("token-1");

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("grant_type=client_credentials")
                .contains("client_id=skateboard-notification-be")
                .contains("client_secret=secret");
    }

    @Test
    void reusesACachedTokenInsteadOfFetchingAgain() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"access_token\":\"token-1\",\"expires_in\":300}")
                .addHeader("Content-Type", "application/json"));

        provider.getAccessToken();
        provider.getAccessToken();

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void fetchesAFreshTokenOnceTheCachedOneIsNearExpiry() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"access_token\":\"token-1\",\"expires_in\":10}")
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"access_token\":\"token-2\",\"expires_in\":300}")
                .addHeader("Content-Type", "application/json"));

        assertThat(provider.getAccessToken()).isEqualTo("token-1");
        // expires_in=10 is inside EXPIRY_SAFETY_MARGIN (30s), so the very next
        // call must already consider it stale.
        assertThat(provider.getAccessToken()).isEqualTo("token-2");
    }

    @Test
    void returnsNullWhenTheTokenEndpointCannotBeReached() throws Exception {
        server.shutdown();

        assertThat(provider.getAccessToken()).isNull();
    }

    @Test
    void returnsNullWhenTheResponseCarriesNoAccessToken() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}")
                .addHeader("Content-Type", "application/json"));

        assertThat(provider.getAccessToken()).isNull();
    }
}
