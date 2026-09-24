package com.skateboard.notification.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.SendTestNotificationUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Sends a fixed test push to the caller's own devices, so "am I receiving
 * notifications?" can be answered without waiting for an episode to publish.
 *
 * <p>Addressed to one user by construction: the devices come from
 * {@link DeviceRepositoryPort#findEnabledDevicesOfUser}, never from the
 * fan-out query, so this cannot reach anybody else. Preferences are skipped on
 * purpose — a user who muted NEW_PODCAST and then taps "test" is asking whether
 * the pipe works, and a silent "0 sent" would answer the wrong question.
 *
 * <p>Recorded and dispatched through the same two phases as an event-driven
 * notification, so a test that Expo does not accept straight away is retried,
 * and one it does accept is receipt-polled like any other.
 */
@Service
public class SendTestNotificationService implements SendTestNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendTestNotificationService.class);

    private static final String REFERENCE_TYPE = "TEST";

    private final DeviceRepositoryPort deviceRepositoryPort;
    private final NotificationTemplateResolver templateResolver;
    private final NotificationRecorder notificationRecorder;
    private final DispatchNotificationService dispatchNotificationService;
    private final ObjectMapper objectMapper;

    public SendTestNotificationService(DeviceRepositoryPort deviceRepositoryPort,
                                       NotificationTemplateResolver templateResolver,
                                       NotificationRecorder notificationRecorder,
                                       DispatchNotificationService dispatchNotificationService,
                                       ObjectMapper objectMapper) {
        this.deviceRepositoryPort = deviceRepositoryPort;
        this.templateResolver = templateResolver;
        this.notificationRecorder = notificationRecorder;
        this.dispatchNotificationService = dispatchNotificationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result execute(Input input) {
        List<NotificationDevice> devices =
                deviceRepositoryPort.findEnabledDevicesOfUser(input.tenantId(), input.userId());

        if (devices.isEmpty()) {
            // Nothing to write either: a notification row with no recipient is
            // noise in the history, and the answer is already known.
            log.info("userId={} tenantId={} requested a test notification but has no enabled device",
                    input.userId(), input.tenantId());
            return new Result(0, 0, 0, 0, 0);
        }

        NotificationTemplateResolver.Template template =
                templateResolver.resolve(NotificationType.TEST_NOTIFICATION, Map.of());

        Notification draft = Notification.create(
                input.tenantId(),
                NotificationType.TEST_NOTIFICATION,
                template.title(),
                template.body(),
                null,
                REFERENCE_TYPE,
                null,
                buildData());

        PreparedDispatch prepared = notificationRecorder.recordDirect(draft, devices);
        DispatchNotificationService.Result dispatch = dispatchNotificationService.send(prepared);

        log.info("userId={} notificationId={} test notification devices={} sent={} retryable={} failed={} invalidTokens={}",
                input.userId(), prepared.notification().getId(), dispatch.devicesTargeted(), dispatch.sent(),
                dispatch.retryable(), dispatch.failed(), dispatch.invalidTokens());

        return new Result(dispatch.devicesTargeted(), dispatch.sent(), dispatch.retryable(),
                dispatch.failed(), dispatch.invalidTokens());
    }

    /**
     * Carries a type but no target: the app has nowhere to route a test to,
     * and an unknown targetType already lands on its default screen.
     */
    private String buildData() {
        try {
            return objectMapper.writeValueAsString(Map.of("type", NotificationType.TEST_NOTIFICATION.name()));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
