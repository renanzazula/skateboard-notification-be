package com.skateboard.notification.application.service;

import com.skateboard.notification.application.port.in.ListInboxUseCase;
import com.skateboard.notification.application.port.out.InboxRepositoryPort;
import com.skateboard.notification.domain.model.InboxEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ListInboxService implements ListInboxUseCase {

    static final int MAX_PAGE_SIZE = 50;

    private final InboxRepositoryPort inboxRepositoryPort;

    public ListInboxService(InboxRepositoryPort inboxRepositoryPort) {
        this.inboxRepositoryPort = inboxRepositoryPort;
    }

    /**
     * Fetches one row past the page to answer hasMore, instead of a COUNT over
     * the user's whole history on every scroll.
     */
    @Override
    @Transactional(readOnly = true)
    public Result execute(Input input) {
        if (input.page() < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (input.size() < 1 || input.size() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        List<InboxEntry> fetched = inboxRepositoryPort.findPage(input.userId(), input.tenantId(),
                Math.multiplyExact(input.page(), input.size()), input.size() + 1);
        boolean hasMore = fetched.size() > input.size();
        List<InboxEntry> entries = hasMore ? fetched.subList(0, input.size()) : fetched;

        return new Result(List.copyOf(entries), input.page(), input.size(), hasMore);
    }
}
