package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.out.EmailDeliveryRepositoryPort;
import com.skateboard.notification.application.port.out.ProcessedEventPort;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes everything GUEST_APPLICATION_SUBMITTED produces, in one
 * transaction: the idempotency claim, the admins' in-app notification (if
 * any recipients are configured) and the PENDING email rows (applicant
 * confirmation plus one per emailable admin) — same claim-inside-the-
 * transaction reasoning as {@link NotificationRecorder#recordEvent}.
 *
 * <p>Nothing is sent here; see {@link DispatchNotificationService} and
 * {@link DispatchEmailService} for why the provider calls are kept outside
 * the transaction.
 */
@Service
public class GuestApplicationNotificationRecorder {

    private static final Logger log = LoggerFactory.getLogger(GuestApplicationNotificationRecorder.class);

    public record Recorded(Optional<PreparedDispatch> pushDispatch, List<EmailDelivery> emailDeliveries) {

        public static Recorded empty() {
            return new Recorded(Optional.empty(), List.of());
        }
    }

    private final ProcessedEventPort processedEventPort;
    private final NotificationRecorder notificationRecorder;
    private final EmailDeliveryRepositoryPort emailDeliveryRepositoryPort;

    public GuestApplicationNotificationRecorder(ProcessedEventPort processedEventPort,
                                                NotificationRecorder notificationRecorder,
                                                EmailDeliveryRepositoryPort emailDeliveryRepositoryPort) {
        this.processedEventPort = processedEventPort;
        this.notificationRecorder = notificationRecorder;
        this.emailDeliveryRepositoryPort = emailDeliveryRepositoryPort;
    }

    /**
     * @param adminNotificationDraft null when no admin recipients are
     *                               configured — nothing in-app is recorded
     * @param adminDevices           push devices for the same recipients;
     *                               may be a subset of {@code adminUserIds}
     *                               or empty
     * @return empty when the event was already processed; in that case the
     *         caller must do nothing at all
     */
    @Transactional
    public Optional<Recorded> recordSubmission(UUID eventId, String eventType, Notification adminNotificationDraft,
                                     List<NotificationDevice> adminDevices, List<UUID> adminUserIds,
                                     List<EmailDelivery> emailDeliveries) {
        if (!processedEventPort.claim(eventId, eventType)) {
            log.info("eventId={} already processed; ignoring redelivery", eventId);
            return Optional.empty();
        }

        Optional<PreparedDispatch> pushDispatch = adminNotificationDraft == null
                ? Optional.empty()
                : Optional.of(notificationRecorder.recordForRecipients(adminNotificationDraft, adminDevices, adminUserIds));

        List<EmailDelivery> savedEmails = emailDeliveries.isEmpty()
                ? List.of()
                : emailDeliveryRepositoryPort.saveAll(emailDeliveries);

        return Optional.of(new Recorded(pushDispatch, savedEmails));
    }
}
