package com.skateboard.notification.adapter.out.directory.appconfig;

import com.skateboard.notification.application.port.out.EmailTemplateResolverPort;
import com.skateboard.notification.domain.model.EmailTemplateType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class AppConfigEmailTemplateClientTest {

    @Mock private ServiceAccountTokenProvider tokenProvider;

    private MockWebServer server;
    private AppConfigEmailTemplateClient client;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        server = new MockWebServer();
        server.start();
        client = new AppConfigEmailTemplateClient(WebClient.builder(),
                new AppConfigClientProperties(server.url("/").toString().replaceAll("/$", ""), 2000, 5000),
                tokenProvider);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void returnsTheParsedTemplateOnSuccess() throws InterruptedException {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"id\":\"f0000000-0000-0000-0000-000000000001\",\"type\":\"GUEST_APPLICATION_RECEIVED\","
                        + "\"language\":\"en\",\"subject\":\"Subject {{name}}\",\"body\":\"Body\",\"enabled\":true}")
                .addHeader("Content-Type", "application/json"));

        Optional<EmailTemplateResolverPort.Template> template =
                client.resolve(EmailTemplateType.GUEST_APPLICATION_RECEIVED, "en");

        assertThat(template).isPresent();
        assertThat(template.get().subject()).isEqualTo("Subject {{name}}");
        assertThat(template.get().body()).isEqualTo("Body");
        assertThat(template.get().enabled()).isTrue();

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/email-templates/GUEST_APPLICATION_RECEIVED/en");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token-1");
    }

    @Test
    void isEmptyWhenNoTokenIsAvailable() {
        when(tokenProvider.getAccessToken()).thenReturn(null);

        assertThat(client.resolve(EmailTemplateType.GUEST_APPLICATION_RECEIVED, "en")).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void isEmptyWhenAppConfigBeIsUnreachable() throws Exception {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        server.shutdown();

        assertThat(client.resolve(EmailTemplateType.GUEST_APPLICATION_RECEIVED, "en")).isEmpty();
    }

    @Test
    void isEmptyOnAnErrorResponse() {
        when(tokenProvider.getAccessToken()).thenReturn("token-1");
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{}")
                .addHeader("Content-Type", "application/json"));

        assertThat(client.resolve(EmailTemplateType.GUEST_APPLICATION_ADMIN_NOTIFICATION, "en")).isEmpty();
    }
}
