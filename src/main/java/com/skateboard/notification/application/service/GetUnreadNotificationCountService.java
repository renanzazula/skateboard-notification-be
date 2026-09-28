package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.GetUnreadNotificationCountUseCase;
import com.skateboard.notification.application.port.out.InboxRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GetUnreadNotificationCountService implements GetUnreadNotificationCountUseCase {

    private final InboxRepositoryPort inboxRepositoryPort;

    public GetUnreadNotificationCountService(InboxRepositoryPort inboxRepositoryPort) {
        this.inboxRepositoryPort = inboxRepositoryPort;
    }

    @Override
    @Transactional(readOnly = true)
    public long execute(UUID userId, UUID tenantId) {
        return inboxRepositoryPort.countUnread(userId, tenantId);
    }
}
