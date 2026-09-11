package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.NotificationMetricsPort;
import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
import com.skateboard.notification.application.port.out.ProcessedEventPort;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.UserNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Writes everything an incoming event produces, in one transaction: the
 * idempotency claim, the notification, one recipient row per user and one
 * PENDING delivery row per device.
 *
 * <p>The claim belongs <em>inside</em> this transaction, not before it. Claimed
 * separately and committed early, any later failure would leave the event
 * marked processed with nothing written — and the redelivery would then be
 * recognised as a duplicate and acked, losing the notification for good with
 * no dead-letter to show for it. Rolling the claim back with the rest is what
 * makes a redelivery a genuine retry.
 *
 * <p>Nothing is sent here. See {@link PreparedDispatch} for why the provider
 * call is kept outside the transaction.
 */
@Service
public class NotificationRecorder {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecorder.class);

    private final ProcessedEventPort processedEventPort;
    private final NotificationRepositoryPort notificationRepositoryPort;
    private final DeviceRepositoryPort deviceRepositoryPort;
    private final DeliveryRepositoryPort deliveryRepositoryPort;
    private final NotificationMetricsPort metricsPort;

    public NotificationRecorder(ProcessedEventPort processedEventPort,
                                 NotificationRepositoryPort notificationRepositoryPort,
                                 DeviceRepositoryPort deviceRepositoryPort,
                                 DeliveryRepositoryPort deliveryRepositoryPort,
                                 NotificationMetricsPort metricsPort) {
        this.processedEventPort = processedEventPort;
        this.notificationRepositoryPort = notificationRepositoryPort;
        this.deviceRepositoryPort = deviceRepositoryPort;
        this.deliveryRepositoryPort = deliveryRepositoryPort;
        this.metricsPort = metricsPort;
    }

    /**
     * @return the work to send, or empty when this event was already processed
     *         — in which case the caller must do nothing at all
     */
    @Transactional
    public Optional<PreparedDispatch> recordEvent(UUID eventId, String eventType, Notification draft) {
        if (!processedEventPort.claim(eventId, eventType)) {
            log.info("eventId={} already processed; ignoring redelivery", eventId);
            metricsPort.eventIgnoredAsDuplicate(eventType);
            return Optional.empty();
        }
        metricsPort.eventProcessed(eventType);

        Notification notification = notificationRepositoryPort.save(draft);

        List<NotificationDevice> devices = deviceRepositoryPort
                .findNotifiableDevices(notification.getTenantId(), notification.getType());

        if (devices.isEmpty()) {
            log.info("notificationId={} type={} tenantId={} matched no notifiable devices",
                    notification.getId(), notification.getType(), notification.getTenantId());
            return Optional.of(new PreparedDispatch(notification, List.of(), List.of()));
        }

        recordRecipients(notification, devices);
        List<NotificationDelivery> deliveries = deliveryRepositoryPort.saveAll(devices.stream()
                .map(device -> NotificationDelivery.pending(notification.getId(), device.getUserId(),
                        device.getId(), device.getPushProvider()))
                .toList());

        return Optional.of(new PreparedDispatch(notification, devices, deliveries));
    }

    /**
     * One row per distinct user, not per device: three phones belonging to the
     * same person are one notification in their inbox.
     */
    private void recordRecipients(Notification notification, List<NotificationDevice> devices) {
        Set<UUID> userIds = new LinkedHashSet<>();
        devices.forEach(device -> userIds.add(device.getUserId()));

        notificationRepositoryPort.saveRecipients(userIds.stream()
                .map(userId -> UserNotification.create(notification.getId(), userId))
                .toList());
    }
}
