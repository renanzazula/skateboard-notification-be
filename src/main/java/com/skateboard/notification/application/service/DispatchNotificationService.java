package com.skateboard.notification.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.DispatchNotificationUseCase;
import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
import com.skateboard.notification.application.port.out.PushMessage;
import com.skateboard.notification.application.port.out.PushNotificationProviderPort;
import com.skateboard.notification.application.port.out.PushResult;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.UserNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recipient resolution and delivery: the second half of handling an event.
 *
 * <p>Ordering matters here. Recipients and PENDING deliveries are written
 * before anything is sent, so a crash mid-fan-out leaves a record of what was
 * owed rather than nothing at all. Sending then updates rows in place.
 */
@Service
public class DispatchNotificationService implements DispatchNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispatchNotificationService.class);

    private final DeviceRepositoryPort deviceRepositoryPort;
    private final NotificationRepositoryPort notificationRepositoryPort;
    private final DeliveryRepositoryPort deliveryRepositoryPort;
    private final PushNotificationProviderPort pushNotificationProviderPort;
    private final ObjectMapper objectMapper;

    public DispatchNotificationService(DeviceRepositoryPort deviceRepositoryPort,
                                        NotificationRepositoryPort notificationRepositoryPort,
                                        DeliveryRepositoryPort deliveryRepositoryPort,
                                        PushNotificationProviderPort pushNotificationProviderPort,
                                        ObjectMapper objectMapper) {
        this.deviceRepositoryPort = deviceRepositoryPort;
        this.notificationRepositoryPort = notificationRepositoryPort;
        this.deliveryRepositoryPort = deliveryRepositoryPort;
        this.pushNotificationProviderPort = pushNotificationProviderPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result execute(Notification notification) {
        List<NotificationDevice> devices = deviceRepositoryPort
                .findNotifiableDevices(notification.getTenantId(), notification.getType());

        if (devices.isEmpty()) {
            log.info("notificationId={} type={} tenantId={} matched no notifiable devices",
                    notification.getId(), notification.getType(), notification.getTenantId());
            return new Result(0, 0, 0, 0);
        }

        recordRecipients(notification, devices);
        List<NotificationDelivery> deliveries = recordPendingDeliveries(notification, devices);

        Map<String, String> data = readData(notification);
        List<PushMessage> messages = devices.stream()
                .map(device -> new PushMessage(device.getPushToken(), notification.getTitle(),
                        notification.getBody(), data))
                .toList();

        List<PushResult> results = pushNotificationProviderPort.send(messages);
        return applyResults(notification, devices, deliveries, results);
    }

    /**
     * One row per distinct user, not per device: three phones belonging to the
     * same person are one notification in their inbox.
     */
    private void recordRecipients(Notification notification, List<NotificationDevice> devices) {
        Set<UUID> userIds = new LinkedHashSet<>();
        devices.forEach(device -> userIds.add(device.getUserId()));

        List<UserNotification> recipients = userIds.stream()
                .map(userId -> UserNotification.create(notification.getId(), userId))
                .toList();
        notificationRepositoryPort.saveRecipients(recipients);
    }

    private List<NotificationDelivery> recordPendingDeliveries(Notification notification,
                                                                List<NotificationDevice> devices) {
        List<NotificationDelivery> deliveries = devices.stream()
                .map(device -> NotificationDelivery.pending(notification.getId(), device.getUserId(),
                        device.getId(), device.getPushProvider()))
                .toList();
        return deliveryRepositoryPort.saveAll(deliveries);
    }

    private Result applyResults(Notification notification,
                                 List<NotificationDevice> devices,
                                 List<NotificationDelivery> deliveries,
                                 List<PushResult> results) {
        int sent = 0;
        int failed = 0;
        int invalidTokens = 0;
        List<NotificationDevice> toDisable = new ArrayList<>();

        for (int i = 0; i < deliveries.size(); i++) {
            NotificationDelivery delivery = deliveries.get(i);
            NotificationDevice device = devices.get(i);
            PushResult result = i < results.size()
                    ? results.get(i)
                    : PushResult.retryable("Provider returned no result for this message");

            switch (result.outcome()) {
                case ACCEPTED -> {
                    delivery.markSent(result.providerMessageId());
                    sent++;
                }
                case RETRYABLE -> delivery.markRetryable(result.detail());
                case INVALID_TOKEN -> {
                    delivery.markInvalidToken(result.detail());
                    // The token will never work again, so keeping the device
                    // enabled would burn an attempt on every future
                    // notification (spec §25).
                    device.disable();
                    toDisable.add(device);
                    invalidTokens++;
                }
                case REJECTED -> {
                    delivery.markFailed(result.detail());
                    failed++;
                }
            }
            deliveryRepositoryPort.save(delivery);
        }

        if (!toDisable.isEmpty()) {
            deviceRepositoryPort.saveAll(toDisable);
        }

        log.info("notificationId={} type={} tenantId={} devices={} sent={} failed={} invalidTokens={}",
                notification.getId(), notification.getType(), notification.getTenantId(),
                devices.size(), sent, failed, invalidTokens);

        return new Result(devices.size(), sent, failed, invalidTokens);
    }

    /**
     * The stored payload is opaque to the domain, so it is parsed here at the
     * point it becomes a push. A payload that will not parse costs the deep
     * link, not the notification — the user still gets told, they just land on
     * the app's default screen.
     */
    private Map<String, String> readData(Notification notification) {
        try {
            return objectMapper.readValue(notification.getDataJson(), new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("notificationId={} has unreadable data payload; sending without deep-link metadata",
                    notification.getId(), e);
            return Map.of();
        }
    }
}
