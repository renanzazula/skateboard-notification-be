package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.ProcessedEventPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The idempotency ledger (spec §24).
 *
 * <p>Deliberately joins the caller's transaction rather than opening its own.
 * An independently committed claim is worse than no claim: any later failure
 * would leave the event marked processed with nothing written, and the
 * redelivery would then be recognised as a duplicate and acked — losing the
 * notification with no dead-letter to show for it. Rolling back with the
 * caller is what makes a redelivery a real retry. See
 * {@code NotificationRecorder}, which owns that transaction.
 *
 * <p>There is no catch around the insert. A unique-constraint violation marks
 * the transaction rollback-only, so catching it could not let the caller
 * proceed anyway — the commit would throw regardless. Letting it propagate is
 * both honest and correct: the message is retried, and the retry sees the
 * committed row and reports a duplicate.
 */
@Component
public class ProcessedEventPersistenceAdapter implements ProcessedEventPort {

    private final SpringProcessedEventRepository repository;

    public ProcessedEventPersistenceAdapter(SpringProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean claim(UUID eventId, String eventType) {
        if (repository.existsById(eventId)) {
            return false;
        }
        ProcessedEventJpaEntity entity = new ProcessedEventJpaEntity();
        entity.setEventId(eventId);
        entity.setEventType(eventType);
        entity.setProcessedAt(Instant.now());
        repository.saveAndFlush(entity);
        return true;
    }
}
