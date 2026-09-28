package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.MarkNotificationReadUseCase;
import com.skateboard.notification.application.port.out.InboxRepositoryPort;
import com.skateboard.notification.domain.exception.InboxNotificationNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MarkNotificationReadService implements MarkNotificationReadUseCase {

    private final InboxRepositoryPort inboxRepositoryPort;

    public MarkNotificationReadService(InboxRepositoryPort inboxRepositoryPort) {
        this.inboxRepositoryPort = inboxRepositoryPort;
    }

    /**
     * The update runs first because it is the common case. Zero rows changed
     * means either already read — fine, idempotent — or not the caller's,
     * which is the only case worth the second query.
     */
    @Override
    @Transactional
    public void execute(Input input) {
        int changed = inboxRepositoryPort.markRead(input.userId(), input.tenantId(), input.notificationId(),
                Instant.now());
        if (changed == 0
                && !inboxRepositoryPort.isRecipient(input.userId(), input.tenantId(), input.notificationId())) {
            throw new InboxNotificationNotFoundException(input.notificationId());
        }
    }
}
