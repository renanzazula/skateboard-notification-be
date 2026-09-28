package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.InboxEntry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads and read-state writes over one user's inbox. Every method takes the
 * tenant as well as the user, for the same reason the fan-out query does:
 * scoping is part of the contract, not something a caller remembers to add.
 */
public interface InboxRepositoryPort {

    /** Newest first. */
    List<InboxEntry> findPage(UUID userId, UUID tenantId, int offset, int limit);

    long countUnread(UUID userId, UUID tenantId);

    boolean isRecipient(UUID userId, UUID tenantId, UUID notificationId);

    /**
     * Targeted updates rather than load-mutate-save: read state is one column,
     * and two devices of the same user marking things read at once must not
     * overwrite each other. Already-read rows are left alone so the first read
     * time is kept.
     *
     * @return rows changed
     */
    int markRead(UUID userId, UUID tenantId, UUID notificationId, Instant readAt);

    /** @return rows changed */
    int markAllRead(UUID userId, UUID tenantId, Instant receivedUpTo, Instant readAt);
}
