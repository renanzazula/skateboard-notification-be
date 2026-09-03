package com.skateboard.notification.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.HandlePodcastPublishedUseCase;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns "a podcast was published" into "these people get told".
 *
 * <p>This is the policy layer the architecture is built around: the producer
 * described a business fact, and every decision about whether it becomes a
 * notification, what it says and who receives it is made here (spec §2).
 */
@Service
public class HandlePodcastPublishedService implements HandlePodcastPublishedUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandlePodcastPublishedService.class);

    private static final String EVENT_TYPE = "PODCAST_PUBLISHED";
    private static final String REFERENCE_TYPE = "PODCAST";

    private final NotificationTemplateResolver templateResolver;
    private final NotificationRecorder notificationRecorder;
    private final DispatchNotificationService dispatchNotificationService;
    private final ObjectMapper objectMapper;

    public HandlePodcastPublishedService(NotificationTemplateResolver templateResolver,
                                          NotificationRecorder notificationRecorder,
                                          DispatchNotificationService dispatchNotificationService,
                                          ObjectMapper objectMapper) {
        this.templateResolver = templateResolver;
        this.notificationRecorder = notificationRecorder;
        this.dispatchNotificationService = dispatchNotificationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Two phases, and the split is the point. Everything that must be
     * consistent — the idempotency claim, the notification, its recipients and
     * a PENDING delivery per device — commits together in
     * {@link NotificationRecorder}. Only then is anything sent.
     *
     * <p>A failure in the first phase rolls the claim back with the rest, so
     * the redelivery is a real retry and an unfixable message still reaches the
     * dead-letter queue. A failure in the second leaves durable PENDING rows
     * that {@link RetryPendingDeliveriesService} owns. Neither loses the
     * notification silently, which the earlier claim-first-and-commit ordering
     * did.
     */
    @Override
    public Result execute(Input input) {
        NotificationTemplateResolver.Template template = templateResolver
                .resolve(NotificationType.NEW_PODCAST, Map.of("title", nullSafe(input.title())));

        Notification draft = Notification.create(
                input.tenantId(),
                NotificationType.NEW_PODCAST,
                template.title(),
                template.body(),
                input.imageUrl(),
                REFERENCE_TYPE,
                input.podcastId(),
                buildData(input));

        Optional<PreparedDispatch> prepared =
                notificationRecorder.record(input.eventId(), EVENT_TYPE, draft);

        if (prepared.isEmpty()) {
            return Result.duplicate();
        }

        DispatchNotificationService.Result dispatch = dispatchNotificationService.send(prepared.get());

        log.info("eventId={} notificationId={} tenantId={} podcastId={} devices={} sent={}",
                input.eventId(), prepared.get().notification().getId(), input.tenantId(),
                input.podcastId(), dispatch.devicesTargeted(), dispatch.sent());

        return new Result(true, dispatch.devicesTargeted(), dispatch.sent());
    }

    /**
     * Semantic navigation targets rather than a backend-built URL (spec §20):
     * the app owns the mapping from a domain target to a route, so a
     * navigation change ships with the app instead of needing a backend
     * release. targetSlug is what the app actually routes on; targetId is kept
     * because it is the stable identifier.
     */
    private String buildData(Input input) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", NotificationType.NEW_PODCAST.name());
        data.put("targetType", REFERENCE_TYPE);
        data.put("targetId", nullSafe(input.podcastId()));
        data.put("targetSlug", nullSafe(input.slug()));
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            // A map of strings cannot fail to serialize; if it somehow does,
            // an undeep-linked notification still beats no notification.
            log.error("Could not serialize notification data for podcastId={}", input.podcastId(), e);
            return "{}";
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
