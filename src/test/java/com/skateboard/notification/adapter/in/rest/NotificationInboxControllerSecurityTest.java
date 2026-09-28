package com.skateboard.notification.adapter.in.rest;

import com.skateboard.notification.application.port.in.GetUnreadNotificationCountUseCase;
import com.skateboard.notification.application.port.in.ListInboxUseCase;
import com.skateboard.notification.application.port.in.MarkAllNotificationsReadUseCase;
import com.skateboard.notification.application.port.in.MarkNotificationReadUseCase;
import com.skateboard.notification.domain.exception.InboxNotificationNotFoundException;
import com.skateboard.notification.domain.model.InboxEntry;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationType;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The inbox reuses the preferences' FUNC_USER_SELF_READ/FUNC_USER_SELF_UPDATE
 * authorities so it needs no realm change. See
 * {@link NotificationPreferenceControllerSecurityTest} for why
 * AopAutoConfiguration is imported.
 */
@WebMvcTest(controllers = NotificationInboxController.class)
@ImportAutoConfiguration(AopAutoConfiguration.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class,
        NotificationInboxControllerSecurityTest.Config.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "app.security.oauth2.audience=skateboard-notification-be",
        "app.tenancy.default-tenant-id=00000000-0000-0000-0000-000000000001"
})
class NotificationInboxControllerSecurityTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TestConfiguration
    static class Config {
        @Bean
        CurrentUserProvider currentUserProvider() {
            return new CurrentUserProvider(TENANT);
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockBean private ListInboxUseCase listInboxUseCase;
    @MockBean private GetUnreadNotificationCountUseCase getUnreadNotificationCountUseCase;
    @MockBean private MarkNotificationReadUseCase markNotificationReadUseCase;
    @MockBean private MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase;

    @Test
    void rejectsAListWithoutAToken() throws Exception {
        mockMvc.perform(get("/inbox")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAListWithoutSelfRead() throws Exception {
        mockMvc.perform(get("/inbox").with(jwt().authorities(() -> "FUNC_TAB_PODCAST")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsTheCallersNotificationsWithTheirNavigationData() throws Exception {
        Notification notification = Notification.create(TENANT, NotificationType.NEW_PODCAST,
                "New podcast available", "Skateboard Podcast #14", null, "PODCAST", "p1",
                "{\"targetType\":\"PODCAST\",\"targetSlug\":\"episode-14\"}");
        given(listInboxUseCase.execute(any())).willReturn(new ListInboxUseCase.Result(
                List.of(new InboxEntry(notification, null)), 0, 20, false));

        mockMvc.perform(get("/inbox").with(caller("FUNC_USER_SELF_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].notificationId").value(notification.getId().toString()))
                .andExpect(jsonPath("$.items[0].title").value("New podcast available"))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[0].data.targetSlug").value("episode-14"))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(listInboxUseCase).execute(new ListInboxUseCase.Input(USER, TENANT, 0, 20));
    }

    @Test
    void anOversizedPageIsABadRequestNotAServerError() throws Exception {
        mockMvc.perform(get("/inbox").param("size", "500").with(caller("FUNC_USER_SELF_READ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsTheUnreadCount() throws Exception {
        given(getUnreadNotificationCountUseCase.execute(USER, TENANT)).willReturn(6L);

        mockMvc.perform(get("/inbox/unread-count").with(caller("FUNC_USER_SELF_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(6));
    }

    @Test
    void aReadAuthorityIsNotEnoughToMarkRead() throws Exception {
        mockMvc.perform(post("/inbox/" + UUID.randomUUID() + "/read").with(caller("FUNC_USER_SELF_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void marksOneRead() throws Exception {
        UUID notificationId = UUID.randomUUID();

        mockMvc.perform(post("/inbox/" + notificationId + "/read").with(caller("FUNC_USER_SELF_UPDATE")))
                .andExpect(status().isNoContent());

        verify(markNotificationReadUseCase)
                .execute(new MarkNotificationReadUseCase.Input(USER, TENANT, notificationId));
    }

    @Test
    void markingANotificationTheCallerNeverReceivedIsNotFound() throws Exception {
        UUID notificationId = UUID.randomUUID();
        willThrow(new InboxNotificationNotFoundException(notificationId))
                .given(markNotificationReadUseCase).execute(any());

        mockMvc.perform(post("/inbox/" + notificationId + "/read").with(caller("FUNC_USER_SELF_UPDATE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void marksAllReadUpToTheGivenInstant() throws Exception {
        mockMvc.perform(post("/inbox/read-all").with(caller("FUNC_USER_SELF_UPDATE"))
                        .contentType("application/json")
                        .content("{\"before\":\"2026-09-28T10:00:00Z\"}"))
                .andExpect(status().isNoContent());

        verify(markAllNotificationsReadUseCase).execute(new MarkAllNotificationsReadUseCase.Input(
                USER, TENANT, Instant.parse("2026-09-28T10:00:00Z")));
    }

    @Test
    void marksAllReadWithNoCutOff() throws Exception {
        mockMvc.perform(post("/inbox/read-all").with(caller("FUNC_USER_SELF_UPDATE"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNoContent());

        verify(markAllNotificationsReadUseCase)
                .execute(new MarkAllNotificationsReadUseCase.Input(USER, TENANT, null));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor caller(String authority) {
        return jwt().jwt(builder -> builder.subject(USER.toString())).authorities(() -> authority);
    }
}
