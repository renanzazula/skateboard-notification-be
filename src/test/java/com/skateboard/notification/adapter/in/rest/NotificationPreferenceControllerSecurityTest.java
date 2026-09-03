package com.skateboard.notification.adapter.in.rest;

import com.skateboard.notification.application.port.in.GetNotificationPreferencesUseCase;
import com.skateboard.notification.application.port.in.UpdateNotificationPreferencesUseCase;
import com.skateboard.notification.domain.model.NotificationPreferences;
import com.skateboard.notification.infrastructure.security.CurrentUserProvider;
import com.skateboard.notification.infrastructure.security.SecurityConfig;
import com.skateboard.notification.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preferences kept the FUNC_USER_SELF_READ/FUNC_USER_SELF_UPDATE authorities
 * they carried while skateboard-user-be served them, so that taking the
 * feature over needs no realm change and no frontend change. These assertions
 * are what stop that from being quietly broken.
 */
/*
 * @ImportAutoConfiguration(AopAutoConfiguration.class) is load-bearing, not
 * tidying. These controllers implement generated API interfaces, and
 * @PreAuthorize needs to proxy them. A @WebMvcTest slice leaves
 * AopAutoConfiguration out, so the proxy defaults to a JDK dynamic one: it
 * implements the interface but carries no @RestController, which makes
 * RequestMappingHandlerMapping stop treating the bean as a handler and every
 * route 404s. Production has that autoconfiguration and therefore class-based
 * proxies; importing it here reproduces production rather than working around
 * the slice.
 */
@WebMvcTest(controllers = NotificationPreferenceController.class)
@ImportAutoConfiguration(AopAutoConfiguration.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class,
        NotificationPreferenceControllerSecurityTest.Config.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "app.security.oauth2.audience=skateboard-notification-be",
        "app.tenancy.default-tenant-id=00000000-0000-0000-0000-000000000001"
})
class NotificationPreferenceControllerSecurityTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TestConfiguration
    static class Config {
        @Bean
        CurrentUserProvider currentUserProvider() {
            return new CurrentUserProvider(TENANT);
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockBean private GetNotificationPreferencesUseCase getNotificationPreferencesUseCase;
    @MockBean private UpdateNotificationPreferencesUseCase updateNotificationPreferencesUseCase;

    @Test
    void rejectsAReadWithoutAToken() throws Exception {
        mockMvc.perform(get("/preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAReadWithoutSelfRead() throws Exception {
        mockMvc.perform(get("/preferences").with(jwt().authorities(() -> "FUNC_TAB_PODCAST")))
                .andExpect(status().isForbidden());
    }

    @Test
    void selfReadReturnsTheSameShapeSkateboardUserBeServed() throws Exception {
        UUID user = UUID.randomUUID();
        given(getNotificationPreferencesUseCase.execute(any(), any()))
                .willReturn(NotificationPreferences.defaults(user, TENANT));

        mockMvc.perform(get("/preferences")
                        .with(jwt().jwt(builder -> builder.subject(user.toString()))
                                .authorities(() -> "FUNC_USER_SELF_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.pushEnabled").value(true))
                .andExpect(jsonPath("$.notifications.newPodcastEnabled").value(true));
    }

    @Test
    void aReadAuthorityIsNotEnoughToWrite() throws Exception {
        mockMvc.perform(patch("/preferences")
                        .with(jwt().authorities(() -> "FUNC_USER_SELF_READ"))
                        .contentType("application/json")
                        .content("{\"notifications\":{\"pushEnabled\":false}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void selfUpdateAllowsAPartialWrite() throws Exception {
        UUID user = UUID.randomUUID();
        given(updateNotificationPreferencesUseCase.execute(any()))
                .willReturn(NotificationPreferences.defaults(user, TENANT));

        mockMvc.perform(patch("/preferences")
                        .with(jwt().jwt(builder -> builder.subject(user.toString()))
                                .authorities(() -> "FUNC_USER_SELF_UPDATE"))
                        .contentType("application/json")
                        .content("{\"notifications\":{\"newPodcastEnabled\":false}}"))
                .andExpect(status().isOk());
    }
}
