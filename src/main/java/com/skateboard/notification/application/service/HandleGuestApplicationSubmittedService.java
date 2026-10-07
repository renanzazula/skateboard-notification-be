package com.skateboard.notification.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.HandleGuestApplicationSubmittedUseCase;
import com.skateboard.notification.application.port.out.DeviceRepositoryPort;
import com.skateboard.notification.application.port.out.EmailTemplateResolverPort;
import com.skateboard.notification.application.port.out.GuestApplicationSettingsPort;
import com.skateboard.notification.application.port.out.RecipientDirectoryPort;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.EmailProvider;
import com.skateboard.notification.domain.model.EmailTemplateType;
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
 * <p>Settings (who the admins are) are read live from skateboard-app-config-be
 * on every attempt, including a retry — see {@link GuestApplicationSettingsPort}'s
 * javadoc for why that is deliberate rather than a snapshot. Email copy is
 * resolved separately, also live, via {@link EmailTemplateResolverPort} and
 * rendered by {@link EmailTemplateRenderer}. A settings-read failure
 * degrades gracefully: the applicant still gets the default confirmation
 * text, admins just get nothing this pass (there is no sane default
 * recipient list). A template-read failure degrades the same way: the
 * hardcoded fallback copy below goes out instead.
 */
@Service
public class HandleGuestApplicationSubmittedService implements HandleGuestApplicationSubmittedUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandleGuestApplicationSubmittedService.class);

    private static final String EVENT_TYPE = "GUEST_APPLICATION_SUBMITTED";
    private static final String REFERENCE_TYPE = "GUEST_APPLICATION";

    // V1 resolves templates at a fixed language: the GUEST_APPLICATION_SUBMITTED
    // event carries no applicant-preferred-language field yet. Matches
    // today's actual behavior (English-only) — not a regression.
    private static final String LANGUAGE = "en";

    // Same copy as skateboard-app-config-be's EmailTemplateType defaults,
    // used here only when the template resolver call itself fails — an
    // admin who has actually configured different text always wins because
    // the resolver call succeeded.
    private static final String DEFAULT_CONFIRMATION_SUBJECT = "We've received your podcast guest application";
    private static final String DEFAULT_CONFIRMATION_BODY =
            "Thank you for your interest in joining our podcast, {{name}}! We've received your application "
                    + "and will contact you as soon as possible.";
    private static final String DEFAULT_ADMIN_NOTIFICATION_SUBJECT = "New podcast guest application from {{name}}";
    private static final String DEFAULT_ADMIN_NOTIFICATION_BODY =
            "{{name}} ({{email}}) applied to be a podcast guest.\n\n{{message}}\n\nReview it in the admin panel.";

    private final GuestApplicationSettingsPort settingsPort;
    private final RecipientDirectoryPort recipientDirectoryPort;
    private final DeviceRepositoryPort deviceRepositoryPort;
    private final NotificationTemplateResolver templateResolver;
    private final GuestApplicationNotificationRecorder recorder;
    private final DispatchNotificationService dispatchNotificationService;
    private final DispatchEmailService dispatchEmailService;
    private final EmailTemplateResolverPort emailTemplateResolverPort;
    private final EmailTemplateRenderer emailTemplateRenderer;
    private final ObjectMapper objectMapper;

    public HandleGuestApplicationSubmittedService(GuestApplicationSettingsPort settingsPort,
                                                  RecipientDirectoryPort recipientDirectoryPort,
                                                  DeviceRepositoryPort deviceRepositoryPort,
                                                  NotificationTemplateResolver templateResolver,
                                                  GuestApplicationNotificationRecorder recorder,
                                                  DispatchNotificationService dispatchNotificationService,
                                                  DispatchEmailService dispatchEmailService,
                                                  EmailTemplateResolverPort emailTemplateResolverPort,
                                                  EmailTemplateRenderer emailTemplateRenderer,
                                                  ObjectMapper objectMapper) {
        this.settingsPort = settingsPort;
        this.recipientDirectoryPort = recipientDirectoryPort;
        this.deviceRepositoryPort = deviceRepositoryPort;
        this.templateResolver = templateResolver;
        this.recorder = recorder;
        this.dispatchNotificationService = dispatchNotificationService;
        this.dispatchEmailService = dispatchEmailService;
        this.emailTemplateResolverPort = emailTemplateResolverPort;
        this.emailTemplateRenderer = emailTemplateRenderer;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result execute(Input input) {
        GuestApplicationSettingsPort.Settings settings = settingsPort.getSettings()
                .orElseGet(() -> new GuestApplicationSettingsPort.Settings(false, List.of()));

        List<UUID> recipientIds = settings.recipientIds();
        List<RecipientDirectoryPort.ResolvedRecipient> resolvedRecipients = recipientDirectoryPort.resolve(recipientIds);

        Notification adminDraft = recipientIds.isEmpty() ? null : buildAdminNotification(input);
        List<NotificationDevice> adminDevices = recipientIds.isEmpty() ? List.of() : devicesFor(input, recipientIds);
        List<EmailDelivery> emails = buildEmailDeliveries(input, resolvedRecipients);

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
    private List<EmailDelivery> buildEmailDeliveries(Input input,
                                                      List<RecipientDirectoryPort.ResolvedRecipient> resolvedRecipients) {
        List<EmailDelivery> emails = new ArrayList<>();

        if (input.email() != null && !input.email().isBlank()) {
            EmailTemplateResolverPort.Template template = emailTemplateResolverPort
                    .resolve(EmailTemplateType.GUEST_APPLICATION_RECEIVED, LANGUAGE)
                    .orElse(new EmailTemplateResolverPort.Template(
                            DEFAULT_CONFIRMATION_SUBJECT, DEFAULT_CONFIRMATION_BODY, true));
            Map<String, Object> variables = Map.of("name", nullSafe(input.name()));
            emails.add(EmailDelivery.pending(REFERENCE_TYPE, input.applicationId(), input.email(),
                    emailTemplateRenderer.render(template.subject(), variables),
                    emailTemplateRenderer.render(template.body(), variables), EmailProvider.BREVO));
        }

        List<RecipientDirectoryPort.ResolvedRecipient> emailableAdmins = resolvedRecipients.stream()
                .filter(RecipientDirectoryPort.ResolvedRecipient::isEmailable)
                .toList();
        if (!emailableAdmins.isEmpty()) {
            EmailTemplateResolverPort.Template adminTemplate = emailTemplateResolverPort
                    .resolve(EmailTemplateType.GUEST_APPLICATION_ADMIN_NOTIFICATION, LANGUAGE)
                    .orElse(new EmailTemplateResolverPort.Template(
                            DEFAULT_ADMIN_NOTIFICATION_SUBJECT, DEFAULT_ADMIN_NOTIFICATION_BODY, true));
            Map<String, Object> adminVariables = Map.of(
                    "name", nullSafe(input.name()),
                    "email", nullSafe(input.email()),
                    "message", nullSafe(input.message()));
            String adminSubject = emailTemplateRenderer.render(adminTemplate.subject(), adminVariables);
            String adminBody = emailTemplateRenderer.render(adminTemplate.body(), adminVariables);
            for (RecipientDirectoryPort.ResolvedRecipient recipient : emailableAdmins) {
                emails.add(EmailDelivery.pending(REFERENCE_TYPE, input.applicationId(), recipient.email(),
                        adminSubject, adminBody, EmailProvider.BREVO));
            }
        }

        return emails;
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

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
