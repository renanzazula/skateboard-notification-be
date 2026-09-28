package com.skateboard.notification.adapter.in.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skateboard.notification.application.port.in.GetUnreadNotificationCountUseCase;
import com.skateboard.notification.application.port.in.ListInboxUseCase;
import com.skateboard.notification.application.port.in.MarkAllNotificationsReadUseCase;
import com.skateboard.notification.application.port.in.MarkNotificationReadUseCase;
import com.skateboard.notification.domain.model.InboxEntry;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.infrastructure.security.CurrentUser;
import com.skateboard.notification.infrastructure.security.CurrentUserProvider;
import com.skateboard.notification.infrastructure.web.api.InboxApi;
import com.skateboard.notification.infrastructure.web.dto.InboxItem;
import com.skateboard.notification.infrastructure.web.dto.InboxPageResponse;
import com.skateboard.notification.infrastructure.web.dto.MarkAllReadRequest;
import com.skateboard.notification.infrastructure.web.dto.UnreadCountResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * The caller's notification history — what backs the app's bell and
 * Notifications screen.
 *
 * <p>Same FUNC_USER_SELF_READ/FUNC_USER_SELF_UPDATE authorities as
 * {@link NotificationPreferenceController}: this is the caller's own data, and
 * reusing them needs no change to the production realm.
 */
@RestController
public class NotificationInboxController implements InboxApi {

    private static final Logger log = LoggerFactory.getLogger(NotificationInboxController.class);

    private static final String SELF_READ = "hasAuthority('FUNC_USER_SELF_READ')";
    private static final String SELF_UPDATE = "hasAuthority('FUNC_USER_SELF_UPDATE')";

    private final ListInboxUseCase listInboxUseCase;
    private final GetUnreadNotificationCountUseCase getUnreadNotificationCountUseCase;
    private final MarkNotificationReadUseCase markNotificationReadUseCase;
    private final MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    public NotificationInboxController(ListInboxUseCase listInboxUseCase,
                                       GetUnreadNotificationCountUseCase getUnreadNotificationCountUseCase,
                                       MarkNotificationReadUseCase markNotificationReadUseCase,
                                       MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase,
                                       CurrentUserProvider currentUserProvider,
                                       ObjectMapper objectMapper) {
        this.listInboxUseCase = listInboxUseCase;
        this.getUnreadNotificationCountUseCase = getUnreadNotificationCountUseCase;
        this.markNotificationReadUseCase = markNotificationReadUseCase;
        this.markAllNotificationsReadUseCase = markAllNotificationsReadUseCase;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    @PreAuthorize(SELF_READ)
    public ResponseEntity<InboxPageResponse> listInbox(Integer page, Integer size) {
        CurrentUser caller = currentUserProvider.require();
        ListInboxUseCase.Result result = listInboxUseCase.execute(new ListInboxUseCase.Input(
                caller.id(), caller.tenantId(),
                page == null ? 0 : page,
                size == null ? 20 : size));

        return ResponseEntity.ok(new InboxPageResponse()
                .items(result.entries().stream().map(this::toItem).toList())
                .page(result.page())
                .size(result.size())
                .hasMore(result.hasMore()));
    }

    @Override
    @PreAuthorize(SELF_READ)
    public ResponseEntity<UnreadCountResponse> getInboxUnreadCount() {
        CurrentUser caller = currentUserProvider.require();
        return ResponseEntity.ok(new UnreadCountResponse()
                .count(getUnreadNotificationCountUseCase.execute(caller.id(), caller.tenantId())));
    }

    @Override
    @PreAuthorize(SELF_UPDATE)
    public ResponseEntity<Void> markInboxNotificationRead(UUID notificationId) {
        CurrentUser caller = currentUserProvider.require();
        markNotificationReadUseCase.execute(
                new MarkNotificationReadUseCase.Input(caller.id(), caller.tenantId(), notificationId));
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize(SELF_UPDATE)
    public ResponseEntity<Void> markAllInboxNotificationsRead(MarkAllReadRequest request) {
        CurrentUser caller = currentUserProvider.require();
        Instant before = request == null || request.getBefore() == null ? null : request.getBefore().toInstant();
        markAllNotificationsReadUseCase.execute(
                new MarkAllNotificationsReadUseCase.Input(caller.id(), caller.tenantId(), before));
        return ResponseEntity.noContent().build();
    }

    private InboxItem toItem(InboxEntry entry) {
        Notification notification = entry.notification();
        return new InboxItem()
                .notificationId(notification.getId())
                .type(notification.getType().name())
                .title(notification.getTitle())
                .body(notification.getBody())
                .imageUrl(notification.getImageUrl())
                .data(readData(notification))
                .createdAt(toOffset(notification.getCreatedAt()))
                .readAt(toOffset(entry.readAt()))
                .read(entry.isRead());
    }

    /**
     * Same tolerance as dispatch: a payload that will not parse costs the deep
     * link, not the entry.
     */
    private Map<String, String> readData(Notification notification) {
        try {
            return objectMapper.readValue(notification.getDataJson(), new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("notificationId={} has unreadable data payload; listing it without navigation metadata",
                    notification.getId());
            return Map.of();
        }
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
