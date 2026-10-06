package com.skateboard.notification.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.HandleGuestApplicationSubmittedUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.GuestApplicationSettingsPort;
import com.skateboard.notification.application.port.out.RecipientDirectoryPort;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.EmailProvider;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDevice;
import com.skateboard.notification.domain.model.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns "a user applied to be a podcast guest" into "the admins who review
 * applications are told, and the applicant gets a confirmation" (spec §5,
 * §6). The policy layer for this event, same role
 * {@link HandlePodcastPublishedService} plays for podcasts.
 *
 * <p>Settings (who the admins are, what the confirmation says) are read live
 * from skateboard-app-config-be on every attempt, including a retry — see
 * {@link GuestApplicationSettingsPort}'s javadoc for why that is deliberate
 * rather than a snapshot. A settings-read failure degrades gracefully: the
 * applicant still gets the default confirmation text, admins just get
 * nothing this pass (there is no sane default recipient list).
 */
@Service
public class HandleGuestApplicationSubmittedService implements HandleGuestApplicationSubmittedUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandleGuestApplicationSubmittedService.class);

    private static final String EVENT_TYPE = "GUEST_APPLICATION_SUBMITTED";
    private static final String REFERENCE_TYPE = "GUEST_APPLICATION";

    // Same copy as skateboard-app-config-be's GuestApplicationConfig
    // defaults, used here only when the settings read itself fails — an
    // admin who has actually configured different text always wins because
    // the settings call succeeded.
    private static final String DEFAULT_CONFIRMATION_SUBJECT = "We've received your podcast guest application";
    private static final String DEFAULT_CONFIRMATION_BODY =
            "Thank you for your interest in joining our podcast! We've received your application "
                    + "and will contact you as soon as possible.";

    private final GuestApplicationSettingsPort settingsPort;
    private final RecipientDirectoryPort recipientDirectoryPort;
    private final DeviceRepositoryPort deviceRepositoryPort;
    private final NotificationTemplateResolver templateResolver;
    private final GuestApplicationNotificationRecorder recorder;
    private final DispatchNotificationService dispatchNotificationService;
    private final DispatchEmailService dispatchEmailService;
    private final ObjectMapper objectMapper;

    public HandleGuestApplicationSubmittedService(GuestApplicationSettingsPort settingsPort,
                                                  RecipientDirectoryPort recipientDirectoryPort,
                                                  DeviceRepositoryPort deviceRepositoryPort,
                                                  NotificationTemplateResolver templateResolver,
                                                  GuestApplicationNotificationRecorder recorder,
                                                  DispatchNotificationService dispatchNotificationService,
                                                  DispatchEmailService dispatchEmailService,
                                                  ObjectMapper objectMapper) {
        this.settingsPort = settingsPort;
        this.recipientDirectoryPort = recipientDirectoryPort;
        this.deviceRepositoryPort = deviceRepositoryPort;
        this.templateResolver = templateResolver;
        this.recorder = recorder;
        this.dispatchNotificationService = dispatchNotificationService;
        this.dispatchEmailService = dispatchEmailService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result execute(Input input) {
        GuestApplicationSettingsPort.Settings settings = settingsPort.getSettings()
                .orElseGet(() -> new GuestApplicationSettingsPort.Settings(
                        false, List.of(), DEFAULT_CONFIRMATION_SUBJECT, DEFAULT_CONFIRMATION_BODY));

        List<UUID> recipientIds = settings.recipientIds();
        List<RecipientDirectoryPort.ResolvedRecipient> resolvedRecipients = recipientDirectoryPort.resolve(recipientIds);

        Notification adminDraft = recipientIds.isEmpty() ? null : buildAdminNotification(input);
        List<NotificationDevice> adminDevices = recipientIds.isEmpty() ? List.of() : devicesFor(input, recipientIds);
        List<EmailDelivery> emails = buildEmailDeliveries(input, settings, resolvedRecipients);

        Optional<GuestApplicationNotificationRecorder.Recorded> recorded = recorder.recordSubmission(
                input.eventId(), EVENT_TYPE, adminDraft, adminDevices, recipientIds, emails);

        if (recorded.isEmpty()) {
            return Result.duplicate();
        }

        recorded.get().pushDispatch().ifPresent(dispatchNotificationService::send);
        dispatchEmailService.send(recorded.get().emailDeliveries());

        int adminsNotifiedInApp = adminDraft == null ? 0 : recipientIds.size();
        int emailsQueued = recorded.get().emailDeliveries().size();
        log.info("eventId={} applicationId={} tenantId={} adminsNotifiedInApp={} emailsQueued={}",
                input.eventId(), input.applicationId(), input.tenantId(), adminsNotifiedInApp, emailsQueued);

        return new Result(true, adminsNotifiedInApp, emailsQueued);
    }

    private Notification buildAdminNotification(Input input) {
        NotificationTemplateResolver.Template template = templateResolver.resolve(
                NotificationType.GUEST_APPLICATION_RECEIVED, Map.of("name", nullSafe(input.name())));
        return Notification.create(input.tenantId(), NotificationType.GUEST_APPLICATION_RECEIVED,
                template.title(), template.body(), null, REFERENCE_TYPE, input.applicationId(), buildData(input));
    }

    /** Every enabled device of every selected recipient — the fan-out's preference rules do not apply to a targeted send. */
    private List<NotificationDevice> devicesFor(Input input, List<UUID> recipientIds) {
        List<NotificationDevice> devices = new ArrayList<>();
        for (UUID recipientId : recipientIds) {
            devices.addAll(deviceRepositoryPort.findEnabledDevicesOfUser(input.tenantId(), recipientId));
        }
        return devices;
    }

    /** Applicant confirmation (when they left a usable email) plus one per admin whose address resolved as safe to use. */
    private List<EmailDelivery> buildEmailDeliveries(Input input, GuestApplicationSettingsPort.Settings settings,
                                                      List<RecipientDirectoryPort.ResolvedRecipient> resolvedRecipients) {
        List<EmailDelivery> emails = new ArrayList<>();

        if (input.email() != null && !input.email().isBlank()) {
            String subject = blankToDefault(settings.confirmationSubject(), DEFAULT_CONFIRMATION_SUBJECT);
            String bodyTemplate = blankToDefault(settings.confirmationBody(), DEFAULT_CONFIRMATION_BODY);
            emails.add(EmailDelivery.pending(REFERENCE_TYPE, input.applicationId(), input.email(),
                    subject, renderPlaceholder(bodyTemplate, input.name()), EmailProvider.BREVO));
        }

        for (RecipientDirectoryPort.ResolvedRecipient recipient : resolvedRecipients) {
            if (!recipient.isEmailable()) {
                continue;
            }
            emails.add(EmailDelivery.pending(REFERENCE_TYPE, input.applicationId(), recipient.email(),
                    adminEmailSubject(input), adminEmailBody(input), EmailProvider.BREVO));
        }

        return emails;
    }

    private String adminEmailSubject(Input input) {
        return "New podcast guest application from " + nullSafe(input.name());
    }

    private String adminEmailBody(Input input) {
        return nullSafe(input.name()) + " (" + nullSafe(input.email()) + ") applied to be a podcast guest.\n\n"
                + nullSafe(input.message()) + "\n\nReview it in the admin panel.";
    }

    /**
     * Semantic navigation target, not a backend-built URL — same convention
     * as {@link HandlePodcastPublishedService#buildData}.
     */
    private String buildData(Input input) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", NotificationType.GUEST_APPLICATION_RECEIVED.name());
        data.put("targetType", REFERENCE_TYPE);
        data.put("targetId", nullSafe(input.applicationId()));
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Could not serialize notification data for applicationId={}", input.applicationId(), e);
            return "{}";
        }
    }

    /** Only {name} is supported — matches skateboard-app-config-be's GuestApplicationConfig, which rejects anything else at save time. */
    private String renderPlaceholder(String template, String name) {
        return template.replace("{name}", nullSafe(name));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
