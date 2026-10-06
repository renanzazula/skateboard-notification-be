package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
import com.skateboard.notification.application.port.out.PreferenceRepositoryPort;
import com.skateboard.notification.domain.model.DeliveryStatus;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.infrastructure.push.RetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends deliveries that are still owed one.
 *
 * <p>Without this, {@code markRetryable} is a lie: a delivery would sit PENDING
 * forever, and a single Expo timeout would silently drop a whole fan-out while
 * the message that caused it was acked. The AMQP listener's retry only covers
 * failures that reach it as exceptions — everything after the recorder commits
 * is past that point and needs recovering from the database instead.
 *
 * <p>Attempts are bounded. A delivery that has used its budget is marked FAILED
 * rather than retried forever, so a permanently unreachable device stops
 * costing a request per pass.
 *
 * <p>Preferences are re-checked here, not only when recipients were resolved.
 * A delivery can sit PENDING for minutes, and a user who opts out in that
 * window must not still get the push: "disabled means not sent" has to hold for
 * a retry as much as for the first attempt.
 */
@Service
public class RetryPendingDeliveriesService {

    private static final Logger log = LoggerFactory.getLogger(RetryPendingDeliveriesService.class);

    static final String OPTED_OUT_REASON = "Suppressed: user has disabled this notification type";

    private final DeliveryRepositoryPort deliveryRepositoryPort;
    private final NotificationRepositoryPort notificationRepositoryPort;
    private final DeviceRepositoryPort deviceRepositoryPort;
    private final PreferenceRepositoryPort preferenceRepositoryPort;
    private final DispatchNotificationService dispatchNotificationService;
    private final RetryProperties properties;

    public RetryPendingDeliveriesService(DeliveryRepositoryPort deliveryRepositoryPort,
                                          NotificationRepositoryPort notificationRepositoryPort,
                                          DeviceRepositoryPort deviceRepositoryPort,
                                          PreferenceRepositoryPort preferenceRepositoryPort,
                                          DispatchNotificationService dispatchNotificationService,
                                          RetryProperties properties) {
        this.deliveryRepositoryPort = deliveryRepositoryPort;
        this.notificationRepositoryPort = notificationRepositoryPort;
        this.deviceRepositoryPort = deviceRepositoryPort;
        this.preferenceRepositoryPort = preferenceRepositoryPort;
        this.dispatchNotificationService = dispatchNotificationService;
        this.properties = properties;
    }

    /** @return how many deliveries were re-sent */
    public int run() {
        Instant notAttemptedSince = Instant.now().minus(Duration.ofSeconds(properties.backoffSeconds()));
        List<NotificationDelivery> claimed = deliveryRepositoryPort
                .claimRetryable(properties.maxAttempts(), notAttemptedSince, properties.batchLimit());

        if (claimed.isEmpty()) {
            return 0;
        }

        // Grouped so devices belonging to one notification go out in a single
        // provider batch rather than one request each.
        Map<UUID, List<NotificationDelivery>> byNotification = new LinkedHashMap<>();
        claimed.forEach(delivery -> byNotification
                .computeIfAbsent(delivery.getNotificationId(), key -> new ArrayList<>())
                .add(delivery));

        int resent = 0;
        for (Map.Entry<UUID, List<NotificationDelivery>> entry : byNotification.entrySet()) {
            resent += resend(entry.getKey(), entry.getValue());
        }

        log.info("Retried pending deliveries: {} claimed, {} re-sent", claimed.size(), resent);
        return resent;
    }

    private int resend(UUID notificationId, List<NotificationDelivery> deliveries) {
        Optional<Notification> notification = notificationRepositoryPort.findById(notificationId);
        if (notification.isEmpty()) {
            // The notification is gone, so the delivery can never succeed.
            // Failing it stops the row being reclaimed on every pass.
            deliveries.forEach(delivery -> {
                delivery.markFailed("Notification no longer exists");
                deliveryRepositoryPort.save(delivery);
            });
            return 0;
        }

        List<NotificationDevice> devices = new ArrayList<>();
        List<NotificationDelivery> sendable = new ArrayList<>();
        // One lookup per user: a user with several devices shares one answer.
        Map<UUID, Boolean> allowedByUser = new HashMap<>();

        for (NotificationDelivery delivery : deliveries) {
            classifyForResend(delivery, notification.get(), allowedByUser).ifPresent(device -> {
                devices.add(device);
                sendable.add(delivery);
            });
        }

        if (sendable.isEmpty()) {
            return 0;
        }

        // Budget is checked after the claim incremented it, so a delivery that
        // has just used its last attempt is sent once more and then retired by
        // the next pass rather than being dropped here.
        DispatchNotificationService.Result result = dispatchNotificationService
                .send(new PreparedDispatch(notification.get(), devices, sendable));

        retireExhausted(sendable);
        return result.sent();
    }

    /**
     * Resolves whether one delivery is still worth sending, failing it in
     * place (and saving) when it is not. Split out of {@link #resend} so the
     * loop there has a single outcome per delivery to react to, rather than
     * two separate early-exit branches.
     */
    private Optional<NotificationDevice> classifyForResend(NotificationDelivery delivery, Notification notification,
                                                             Map<UUID, Boolean> allowedByUser) {
        Optional<NotificationDevice> device = deviceRepositoryPort.findById(delivery.getDeviceId());
        // A device that has since been deleted or disabled — most often
        // because its token turned out to be dead, or the user logged out —
        // is not worth another attempt.
        if (device.isEmpty() || !device.get().isEnabled()) {
            delivery.markFailed("Device is no longer registered for push");
            deliveryRepositoryPort.save(delivery);
            return Optional.empty();
        }
        // The user may have opted out after the event was recorded. The
        // delivery is failed rather than left PENDING so it is not
        // reclaimed on every pass, and never sent.
        if (!userAllows(allowedByUser, delivery.getUserId(), notification)) {
            delivery.markFailed(OPTED_OUT_REASON);
            deliveryRepositoryPort.save(delivery);
            log.info("deliveryId={} userId={} type={} suppressed on retry: user opted out",
                    delivery.getId(), delivery.getUserId(), notification.getType());
            return Optional.empty();
        }
        return device;
    }

    private boolean userAllows(Map<UUID, Boolean> cache, UUID userId, Notification notification) {
        // A test push is a user checking the pipe works; preferences never
        // apply to it (see SendTestNotificationService).
        if (notification.getType() == NotificationType.TEST_NOTIFICATION) {
            return true;
        }
        return cache.computeIfAbsent(userId, id -> preferenceRepositoryPort
                .load(id, notification.getTenantId())
                .allows(notification.getType()));
    }

    private void retireExhausted(List<NotificationDelivery> deliveries) {
        deliveries.stream()
                .filter(delivery -> !delivery.hasAttemptsLeft(properties.maxAttempts()))
                .forEach(delivery -> {
                    if (delivery.getStatus() == DeliveryStatus.PENDING) {
                        delivery.markFailed("Giving up after " + delivery.getAttemptCount() + " attempts");
                        deliveryRepositoryPort.save(delivery);
                    }
                });
    }
}
