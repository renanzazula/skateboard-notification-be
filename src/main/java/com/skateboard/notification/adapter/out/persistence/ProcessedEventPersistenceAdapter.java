package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.ProcessedEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The idempotency ledger (spec §24).
 *
 * <p>The claim runs in its own transaction (REQUIRES_NEW) so that losing the
 * race only rolls back the insert attempt, not the caller's work. It relies on
 * the primary key rather than a check-then-insert: two consumers handed the
 * same redelivered message would both pass a SELECT, and only the unique
 * constraint actually decides which one proceeds.
 *
 * <p>Because the row is committed before the notification is written, a crash
 * between the two loses the notification rather than sending it twice. That is
 * the trade this service wants: producers re-emit with a stable event id, so a
 * lost event is recoverable, while a duplicate push is not retractable.
 */
@Component
public class ProcessedEventPersistenceAdapter implements ProcessedEventPort {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventPersistenceAdapter.class);

    private final SpringProcessedEventRepository repository;

    public ProcessedEventPersistenceAdapter(SpringProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID eventId, String eventType) {
        if (repository.existsById(eventId)) {
            return false;
        }
        ProcessedEventJpaEntity entity = new ProcessedEventJpaEntity();
        entity.setEventId(eventId);
        entity.setEventType(eventType);
        entity.setProcessedAt(Instant.now());
        try {
            repository.saveAndFlush(entity);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Another consumer inserted the same id between the check and the
            // write. Theirs wins; this delivery is a duplicate.
            log.debug("Event {} was claimed concurrently", eventId);
            return false;
        }
    }
}
