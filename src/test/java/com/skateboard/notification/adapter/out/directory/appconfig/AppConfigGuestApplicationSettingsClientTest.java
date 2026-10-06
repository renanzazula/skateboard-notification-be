package com.skateboard.notification.adapter.out.directory.appconfig;

import com.skateboard.notification.application.port.out.GuestApplicationSettingsPort;
import com.skateboard.notification.infrastructure.directory.AppConfigClientProperties;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class AppConfigGuestApplicationSettingsClientTest {

    @Mock private ServiceAccountTokenProvider tokenProvider;

    private MockWebServer server;
    private AppConfigGuestApplicationSettingsClient client;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        server = new MockWebServer();
        server.start();
        client = new AppConfigGuestApplicationSettingsClient(WebClient.builder(),
                new AppConfigClientProperties(server.url("/").toString().replaceAll("/$", ""), 2000, 5000),
                tokenProvider);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void returnsTheParsedSettingsOnSuccess() throws InterruptedException {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        UUID recipient = UUID.randomUUID();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"enabled\":true,\"recipientIds\":[\"" + recipient + "\"],"
                        + "\"confirmationSubject\":\"Subject\",\"confirmationBody\":\"Body\"}")
                .addHeader("Content-Type", "application/json"));

        Optional<GuestApplicationSettingsPort.Settings> settings = client.getSettings();

        assertThat(settings).isPresent();
        assertThat(settings.get().enabled()).isTrue();
        assertThat(settings.get().recipientIds()).containsExactly(recipient);
        assertThat(settings.get().confirmationSubject()).isEqualTo("Subject");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/guest-application-settings/admin");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token-1");
    }

    @Test
    void isEmptyWhenNoTokenIsAvailable() {
        when(tokenProvider.getAccessToken()).thenReturn(null);

        assertThat(client.getSettings()).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void isEmptyWhenAppConfigBeIsUnreachable() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        server.shutdown();

        assertThat(client.getSettings()).isEmpty();
    }

    @Test
    void isEmptyOnAnErrorResponse() {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{}")
                .addHeader("Content-Type", "application/json"));

        assertThat(client.getSettings()).isEmpty();
    }

    @Test
    void defaultsRecipientIdsToEmptyWhenAbsent() {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"enabled\":false,\"confirmationSubject\":\"S\",\"confirmationBody\":\"B\"}")
                .addHeader("Content-Type", "application/json"));

        assertThat(client.getSettings().get().recipientIds()).isEmpty();
    }
}
