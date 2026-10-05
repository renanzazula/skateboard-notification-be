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
        List<UUID> inboxRecipients = deviceRepositoryPort.findUsersWithEnabledDevices(notification.getTenantId());

        return Optional.of(recordDeliveries(notification, devices, inboxRecipients));
    }

    /**
     * Records a notification addressed to devices the caller already chose,
     * with no event behind it and so nothing to claim. Same single transaction
     * as {@link #recordEvent}, so the PENDING rows the retry and receipt passes
     * rely on exist before anything is sent.
     */
    @Transactional
    public PreparedDispatch recordDirect(Notification draft, List<NotificationDevice> devices) {
        return recordDeliveries(notificationRepositoryPort.save(draft), devices, List.of());
    }

    /**
     * Records a notification addressed to a caller-chosen set of recipients,
     * also with no event behind it. Unlike {@link #recordDirect}, the inbox
     * audience is the explicit {@code recipientUserIds} rather than "whoever
     * owns a device in {@code devices}" — a targeted send (e.g. selected
     * admins for a guest application) must land in every chosen recipient's
     * inbox even if they have no push device registered; a push is a bonus,
     * not the definition of "notified".
     */
    @Transactional
    public PreparedDispatch recordForRecipients(Notification draft, List<NotificationDevice> devices,
                                                 List<UUID> recipientUserIds) {
        return recordDeliveries(notificationRepositoryPort.save(draft), devices, recipientUserIds);
    }

    /**
     * The inbox audience and the push audience differ: {@code inboxRecipients}
     * ignores preferences, {@code devices} has them applied. Every device owner
     * is added to the inbox too, so a push can never arrive for a notification
     * the recipient's inbox does not have.
     */
    private PreparedDispatch recordDeliveries(Notification notification, List<NotificationDevice> devices,
                                              List<UUID> inboxRecipients) {
        recordRecipients(notification, devices, inboxRecipients);

        if (devices.isEmpty()) {
            log.info("notificationId={} type={} tenantId={} matched no notifiable devices",
                    notification.getId(), notification.getType(), notification.getTenantId());
            return new PreparedDispatch(notification, List.of(), List.of());
        }

        List<NotificationDelivery> deliveries = deliveryRepositoryPort.saveAll(devices.stream()
                .map(device -> NotificationDelivery.pending(notification.getId(), device.getUserId(),
                        device.getId(), device.getPushProvider()))
                .toList());

        return new PreparedDispatch(notification, devices, deliveries);
    }

    /**
     * One row per distinct user, not per device: three phones belonging to the
     * same person are one notification in their inbox.
     */
    private void recordRecipients(Notification notification, List<NotificationDevice> devices,
                                  List<UUID> inboxRecipients) {
        Set<UUID> userIds = new LinkedHashSet<>(inboxRecipients);
        devices.forEach(device -> userIds.add(device.getUserId()));
        if (userIds.isEmpty()) {
            return;
        }

        notificationRepositoryPort.saveRecipients(userIds.stream()
                .map(userId -> UserNotification.create(notification.getId(), userId))
                .toList());
    }
}
