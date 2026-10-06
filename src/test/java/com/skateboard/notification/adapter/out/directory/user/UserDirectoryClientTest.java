package com.skateboard.notification.adapter.out.directory.user;

import com.skateboard.notification.application.port.out.RecipientDirectoryPort;
import com.skateboard.notification.infrastructure.directory.UserDirectoryClientProperties;
import com.skateboard.notification.infrastructure.security.ServiceAccountTokenProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class UserDirectoryClientTest {

    @Mock private ServiceAccountTokenProvider tokenProvider;

    private MockWebServer server;
    private UserDirectoryClient client;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        server = new MockWebServer();
        server.start();
        client = new UserDirectoryClient(WebClient.builder(),
                new UserDirectoryClientProperties(server.url("/").toString().replaceAll("/$", ""), 2000, 5000),
                tokenProvider);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void returnsNothingForAnEmptyRequest() {
        assertThat(client.resolve(List.of())).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void resolvesIdentitiesAndSendsTheBearerToken() throws InterruptedException {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        UUID id = UUID.randomUUID();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("[{\"id\":\"" + id + "\",\"email\":\"a@example.com\",\"emailVerified\":true,\"active\":true}]")
                .addHeader("Content-Type", "application/json"));

        List<RecipientDirectoryPort.ResolvedRecipient> resolved = client.resolve(List.of(id));

        assertThat(resolved).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(id);
            assertThat(r.email()).isEqualTo("a@example.com");
            assertThat(r.isEmailable()).isTrue();
        });

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token-1");
        assertThat(request.getPath()).startsWith("/admin/users/lookup?ids=");
    }

    @Test
    void anUnverifiedOrInactiveIdentityIsNotEmailable() {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        UUID id = UUID.randomUUID();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("[{\"id\":\"" + id + "\",\"email\":\"a@example.com\",\"emailVerified\":false,\"active\":true}]")
                .addHeader("Content-Type", "application/json"));

        assertThat(client.resolve(List.of(id))).singleElement()
                .extracting(RecipientDirectoryPort.ResolvedRecipient::isEmailable).isEqualTo(false);
    }

    @Test
    void isEmptyWhenNoTokenIsAvailable() {
        when(tokenProvider.getAccessToken()).thenReturn(null);

        assertThat(client.resolve(List.of(UUID.randomUUID()))).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void isEmptyWhenUserBeIsUnreachable() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        server.shutdown();

        assertThat(client.resolve(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void batchesRequestsAtOneHundredIds() {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        List<UUID> ids = java.util.stream.IntStream.range(0, 150).mapToObj(i -> UUID.randomUUID()).toList();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]").addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("[]").addHeader("Content-Type", "application/json"));

        client.resolve(ids);

        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
