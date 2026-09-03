package com.skateboard.notification.adapter.in.rest;

import com.skateboard.notification.application.port.in.RegisterDeviceUseCase;
import com.skateboard.notification.application.port.in.RemoveDeviceUseCase;
import com.skateboard.notification.domain.model.DevicePlatform;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.PushProvider;
import com.skateboard.notification.infrastructure.security.CurrentUserProvider;
import com.skateboard.notification.infrastructure.security.SecurityConfig;
import com.skateboard.notification.infrastructure.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authority gate on device registration. The permission strings here are
 * the ones api/openapi.yaml declares as x-required-permissions; if the two
 * drift, this is where it shows up.
 *
 * <p>Scoped to this service's own gate — nothing about what the use cases do
 * with a device is asserted here.
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
@WebMvcTest(controllers = NotificationDeviceController.class)
@ImportAutoConfiguration(AopAutoConfiguration.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class,
        NotificationDeviceControllerSecurityTest.Config.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:0/realms/test",
        "app.security.oauth2.audience=skateboard-notification-be",
        "app.tenancy.default-tenant-id=00000000-0000-0000-0000-000000000001"
})
class NotificationDeviceControllerSecurityTest {

    private static final String BODY = """
            {"platform":"IOS","provider":"EXPO","pushToken":"ExponentPushToken[abc]","appVersion":"1.5.0"}""";

    @TestConfiguration
    static class Config {
        @Bean
        CurrentUserProvider currentUserProvider() {
            return new CurrentUserProvider(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockBean private RegisterDeviceUseCase registerDeviceUseCase;
    @MockBean private RemoveDeviceUseCase removeDeviceUseCase;

    @Test
    void rejectsRegistrationWithoutAToken() throws Exception {
        mockMvc.perform(put("/devices/install-1").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenMissingTheDeviceAuthority() throws Exception {
        mockMvc.perform(put("/devices/install-1")
                        .with(jwt().authorities(() -> "FUNC_SOME_OTHER_PERMISSION"))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsRegistrationWithTheDeviceAuthority() throws Exception {
        given(registerDeviceUseCase.execute(any())).willReturn(NotificationDevice.register(
                UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"), "install-1",
                DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[abc]", "1.5.0", "iPhone"));

        mockMvc.perform(put("/devices/install-1")
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))
                                .authorities(() -> "FUNC_NOTIFICATION_DEVICE_MANAGE"))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());
    }

    /**
     * provider is optional in the spec, so this is a real path rather than an
     * unreachable default: a build that predates the field registers fine
     * instead of getting a 400.
     */
    @Test
    void acceptsARegistrationThatOmitsTheProvider() throws Exception {
        given(registerDeviceUseCase.execute(any())).willReturn(NotificationDevice.register(
                UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"), "install-1",
                DevicePlatform.IOS, PushProvider.EXPO, "ExponentPushToken[abc]", "1.5.0", "iPhone"));

        mockMvc.perform(put("/devices/install-1")
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))
                                .authorities(() -> "FUNC_NOTIFICATION_DEVICE_MANAGE"))
                        .contentType("application/json")
                        .content("{\"platform\":\"IOS\",\"pushToken\":\"ExponentPushToken[abc]\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<RegisterDeviceUseCase.Input> captor =
                ArgumentCaptor.forClass(RegisterDeviceUseCase.Input.class);
        verify(registerDeviceUseCase).execute(captor.capture());
        assertThat(captor.getValue().provider()).isEqualTo(PushProvider.EXPO);
    }

    @Test
    void rejectsRemovalWithoutTheDeviceAuthority() throws Exception {
        mockMvc.perform(delete("/devices/install-1")
                        .with(jwt().authorities(() -> "FUNC_USER_SELF_READ")))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsRemovalWithTheDeviceAuthority() throws Exception {
        mockMvc.perform(delete("/devices/install-1")
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))
                                .authorities(() -> "FUNC_NOTIFICATION_DEVICE_MANAGE")))
                .andExpect(status().isNoContent());
    }
}
