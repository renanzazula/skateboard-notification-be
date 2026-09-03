package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.RetentionRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Each batch commits on its own — the service method that drives the loop is
 * deliberately not transactional — so a long backlog drains over several passes
 * and a failure keeps whatever progress was already made.
 */
@Component
public class RetentionPersistenceAdapter implements RetentionRepositoryPort {

    private final SpringNotificationRepository notificationRepository;
    private final SpringProcessedEventRepository processedEventRepository;

    public RetentionPersistenceAdapter(SpringNotificationRepository notificationRepository,
                                       SpringProcessedEventRepository processedEventRepository) {
        this.notificationRepository = notificationRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @Override
    @Transactional
    public int deleteNotificationsOlderThan(Instant cutoff, int batchSize) {
        return notificationRepository.deleteCreatedBefore(cutoff, batchSize);
    }

    @Override
    @Transactional
    public int deleteProcessedEventsOlderThan(Instant cutoff, int batchSize) {
        return processedEventRepository.deleteProcessedBefore(cutoff, batchSize);
    }
}
