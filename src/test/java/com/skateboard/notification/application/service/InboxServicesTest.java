package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.ListInboxUseCase;
import com.skateboard.notification.application.port.in.MarkAllNotificationsReadUseCase;
import com.skateboard.notification.application.port.in.MarkNotificationReadUseCase;
import com.skateboard.notification.application.port.out.InboxRepositoryPort;
import com.skateboard.notification.domain.exception.InboxNotificationNotFoundException;
import com.skateboard.notification.domain.model.InboxEntry;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The four inbox use cases. Each is thin over {@link InboxRepositoryPort}; what
 * is worth pinning is the paging arithmetic, the 404-vs-idempotent split on a
 * single read, and the clamp on read-all's cut-off.
 */
class InboxServicesTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NOTIFICATION = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private InboxRepositoryPort inboxRepositoryPort;

    private ListInboxService listInboxService;
    private GetUnreadNotificationCountService unreadCountService;
    private MarkNotificationReadService markReadService;
    private MarkAllNotificationsReadService markAllReadService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listInboxService = new ListInboxService(inboxRepositoryPort);
        unreadCountService = new GetUnreadNotificationCountService(inboxRepositoryPort);
        markReadService = new MarkNotificationReadService(inboxRepositoryPort);
        markAllReadService = new MarkAllNotificationsReadService(inboxRepositoryPort);
    }

    @Test
    void fetchesOneRowPastThePageToAnswerHasMore() {
        when(inboxRepositoryPort.findPage(USER, TENANT, 20, 11)).thenReturn(entries(11));

        ListInboxUseCase.Result result = listInboxService.execute(new ListInboxUseCase.Input(USER, TENANT, 2, 10));

        assertThat(result.entries()).hasSize(10);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
    }

    @Test
    void theLastPageHasNoMore() {
        when(inboxRepositoryPort.findPage(USER, TENANT, 0, 21)).thenReturn(entries(3));

        ListInboxUseCase.Result result = listInboxService.execute(new ListInboxUseCase.Input(USER, TENANT, 0, 20));

        assertThat(result.entries()).hasSize(3);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void rejectsPagingOutsideTheContract() {
        assertThatThrownBy(() -> listInboxService.execute(new ListInboxUseCase.Input(USER, TENANT, -1, 20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listInboxService.execute(new ListInboxUseCase.Input(USER, TENANT, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listInboxService.execute(new ListInboxUseCase.Input(USER, TENANT, 0, 51)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(inboxRepositoryPort);
    }

    @Test
    void unreadCountIsScopedToTheCaller() {
        when(inboxRepositoryPort.countUnread(USER, TENANT)).thenReturn(6L);

        assertThat(unreadCountService.execute(USER, TENANT)).isEqualTo(6L);
    }

    @Test
    void markingReadNeedsNoSecondQueryWhenARowChanged() {
        when(inboxRepositoryPort.markRead(eq(USER), eq(TENANT), eq(NOTIFICATION), any())).thenReturn(1);

        markReadService.execute(new MarkNotificationReadUseCase.Input(USER, TENANT, NOTIFICATION));

        verify(inboxRepositoryPort, never()).isRecipient(any(), any(), any());
    }

    @Test
    void markingAnAlreadyReadNotificationReadIsIdempotent() {
        when(inboxRepositoryPort.markRead(eq(USER), eq(TENANT), eq(NOTIFICATION), any())).thenReturn(0);
        when(inboxRepositoryPort.isRecipient(USER, TENANT, NOTIFICATION)).thenReturn(true);

        markReadService.execute(new MarkNotificationReadUseCase.Input(USER, TENANT, NOTIFICATION));
    }

    @Test
    void markingSomeoneElsesNotificationReadIsNotFound() {
        when(inboxRepositoryPort.markRead(eq(USER), eq(TENANT), eq(NOTIFICATION), any())).thenReturn(0);
        when(inboxRepositoryPort.isRecipient(USER, TENANT, NOTIFICATION)).thenReturn(false);

        assertThatThrownBy(() ->
                markReadService.execute(new MarkNotificationReadUseCase.Input(USER, TENANT, NOTIFICATION)))
                .isInstanceOf(InboxNotificationNotFoundException.class);
    }

    @Test
    void readAllHonoursACutOffInThePast() {
        Instant loadedAt = Instant.now().minus(Duration.ofMinutes(5));

        markAllReadService.execute(new MarkAllNotificationsReadUseCase.Input(USER, TENANT, loadedAt));

        verify(inboxRepositoryPort).markAllRead(eq(USER), eq(TENANT), eq(loadedAt), any());
    }

    /** A skewed client clock must not pre-read notifications that have not arrived yet. */
    @Test
    void readAllClampsAFutureCutOffToNow() {
        Instant future = Instant.now().plus(Duration.ofDays(1));

        markAllReadService.execute(new MarkAllNotificationsReadUseCase.Input(USER, TENANT, future));

        ArgumentCaptor<Instant> upTo = ArgumentCaptor.forClass(Instant.class);
        verify(inboxRepositoryPort).markAllRead(eq(USER), eq(TENANT), upTo.capture(), any());
        assertThat(upTo.getValue()).isBefore(future);
    }

    @Test
    void readAllWithNoCutOffMeansNow() {
        Instant before = Instant.now();

        markAllReadService.execute(new MarkAllNotificationsReadUseCase.Input(USER, TENANT, null));

        ArgumentCaptor<Instant> upTo = ArgumentCaptor.forClass(Instant.class);
        verify(inboxRepositoryPort).markAllRead(eq(USER), eq(TENANT), upTo.capture(), any());
        assertThat(upTo.getValue()).isAfterOrEqualTo(before);
    }

    private List<InboxEntry> entries(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new InboxEntry(Notification.create(TENANT, NotificationType.NEW_PODCAST,
                        "New podcast available", "Episode " + i, null, "PODCAST", "p" + i, "{}"), null))
                .toList();
    }
}
