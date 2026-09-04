package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.DeliveryRepositoryPort;
import com.skateboard.notification.domain.model.DeliveryChannel;
import com.skateboard.notification.domain.model.DeliveryStatus;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.PushProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class DeliveryPersistenceAdapter implements DeliveryRepositoryPort {

    private final SpringNotificationDeliveryRepository repository;

    public DeliveryPersistenceAdapter(SpringNotificationDeliveryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public List<NotificationDelivery> saveAll(List<NotificationDelivery> deliveries) {
        List<NotificationDeliveryJpaEntity> entities = deliveries.stream().map(this::toEntity).toList();
        return repository.saveAll(entities).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public NotificationDelivery save(NotificationDelivery delivery) {
        return toDomain(repository.save(toEntity(delivery)));
    }

    /**
     * Selects under a row lock and stamps the attempt in the same transaction,
     * so the claim is durable before the caller starts sending. The lock is
     * released at commit; what keeps another instance off these rows after
     * that is the attempt timestamp, which moves them out of the next poll's
     * window.
     */
    @Override
    @Transactional
    public List<NotificationDelivery> claimRetryable(int maxAttempts, Instant notAttemptedSince, int limit) {
        List<NotificationDeliveryJpaEntity> locked =
                repository.lockRetryable(maxAttempts, notAttemptedSince, limit);
        if (locked.isEmpty()) {
            return List.of();
        }
        List<NotificationDelivery> claimed = locked.stream().map(this::toDomain).toList();
        claimed.forEach(NotificationDelivery::beginAttempt);
        return toDomainAll(repository.saveAll(claimed.stream().map(this::toEntity).toList()));
    }

    @Override
    public long countPendingDeliveries() {
        return repository.countByStatus(DeliveryStatus.PENDING.name());
    }

    @Override
    public List<NotificationDelivery> findAwaitingReceipt(Instant sentAfter, Instant sentBefore, int limit) {
        return toDomainAll(repository.findAwaitingReceipt(sentAfter, sentBefore, limit));
    }

    private List<NotificationDelivery> toDomainAll(List<NotificationDeliveryJpaEntity> entities) {
        return entities.stream().map(this::toDomain).toList();
    }

    private NotificationDelivery toDomain(NotificationDeliveryJpaEntity entity) {
        return NotificationDelivery.reconstitute(
                entity.getId(),
                entity.getNotificationId(),
                entity.getUserId(),
                entity.getDeviceId(),
                DeliveryChannel.valueOf(entity.getChannel()),
                PushProvider.valueOf(entity.getProvider()),
                DeliveryStatus.valueOf(entity.getStatus()),
                entity.getProviderMessageId(),
                entity.getAttemptCount(),
                entity.getLastAttemptAt(),
                entity.getFailureReason(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private NotificationDeliveryJpaEntity toEntity(NotificationDelivery delivery) {
        NotificationDeliveryJpaEntity entity = new NotificationDeliveryJpaEntity();
        entity.setId(delivery.getId());
        entity.setNotificationId(delivery.getNotificationId());
        entity.setUserId(delivery.getUserId());
        entity.setDeviceId(delivery.getDeviceId());
        entity.setChannel(delivery.getChannel().name());
        entity.setProvider(delivery.getProvider().name());
        entity.setStatus(delivery.getStatus().name());
        entity.setProviderMessageId(delivery.getProviderMessageId());
        entity.setAttemptCount(delivery.getAttemptCount());
        entity.setLastAttemptAt(delivery.getLastAttemptAt());
        entity.setFailureReason(delivery.getFailureReason());
        entity.setCreatedAt(delivery.getCreatedAt());
        entity.setUpdatedAt(delivery.getUpdatedAt());
        return entity;
    }
}
