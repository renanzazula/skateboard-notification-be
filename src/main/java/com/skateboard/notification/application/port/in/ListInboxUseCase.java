package com.skateboard.notification.application.port.in;

import com.skateboard.notification.domain.model.InboxEntry;

import java.util.List;
import java.util.UUID;

public interface ListInboxUseCase {

    Result execute(Input input);

    record Input(UUID userId, UUID tenantId, int page, int size) {
    }

    record Result(List<InboxEntry> entries, int page, int size, boolean hasMore) {
    }
}
