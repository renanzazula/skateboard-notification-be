package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.MarkAllNotificationsReadUseCase;
import com.skateboard.notification.application.port.out.InboxRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MarkAllNotificationsReadService implements MarkAllNotificationsReadUseCase {

    private final InboxRepositoryPort inboxRepositoryPort;

    public MarkAllNotificationsReadService(InboxRepositoryPort inboxRepositoryPort) {
        this.inboxRepositoryPort = inboxRepositoryPort;
    }

    /**
     * A {@code before} in the future is clamped to now: it can only come from
     * a skewed client clock, and honouring it would mark notifications that
     * have not arrived yet as read the moment they do.
     */
    @Override
    @Transactional
    public int execute(Input input) {
        Instant now = Instant.now();
        Instant upTo = input.before() == null || input.before().isAfter(now) ? now : input.before();
        return inboxRepositoryPort.markAllRead(input.userId(), input.tenantId(), upTo, now);
    }
}
