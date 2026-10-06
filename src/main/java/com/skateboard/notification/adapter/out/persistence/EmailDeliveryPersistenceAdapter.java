package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.EmailDeliveryRepositoryPort;
import com.skateboard.notification.domain.model.EmailDelivery;
import com.skateboard.notification.domain.model.EmailDeliveryStatus;
import com.skateboard.notification.domain.model.EmailProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class EmailDeliveryPersistenceAdapter implements EmailDeliveryRepositoryPort {

    private final SpringEmailDeliveryRepository repository;

    public EmailDeliveryPersistenceAdapter(SpringEmailDeliveryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public List<EmailDelivery> saveAll(List<EmailDelivery> deliveries) {
        List<EmailDeliveryJpaEntity> entities = deliveries.stream().map(this::toEntity).toList();
        return repository.saveAll(entities).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public EmailDelivery save(EmailDelivery delivery) {
        return toDomain(repository.save(toEntity(delivery)));
    }

    @Override
    @Transactional
    public List<EmailDelivery> claimRetryable(int maxAttempts, Instant notAttemptedSince, int limit) {
        List<EmailDeliveryJpaEntity> locked = repository.lockRetryable(maxAttempts, notAttemptedSince, limit);
        if (locked.isEmpty()) {
            return List.of();
        }
        List<EmailDelivery> claimed = locked.stream().map(this::toDomain).toList();
        claimed.forEach(EmailDelivery::beginAttempt);
        return claimed.stream().map(this::toEntity).map(repository::save).map(this::toDomain).toList();
    }

    @Override
    public long countPendingDeliveries() {
        return repository.countByStatus(EmailDeliveryStatus.PENDING.name());
    }

    private EmailDelivery toDomain(EmailDeliveryJpaEntity e) {
        return EmailDelivery.reconstitute(
                e.getId(), e.getReferenceType(), e.getReferenceId(), e.getRecipientEmail(),
                e.getSubject(), e.getBody(), EmailProvider.valueOf(e.getProvider()),
                EmailDeliveryStatus.valueOf(e.getStatus()), e.getProviderMessageId(), e.getAttemptCount(),
                e.getLastAttemptAt(), e.getFailureReason(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private EmailDeliveryJpaEntity toEntity(EmailDelivery d) {
        EmailDeliveryJpaEntity e = new EmailDeliveryJpaEntity();
        e.setId(d.getId());
        e.setReferenceType(d.getReferenceType());
        e.setReferenceId(d.getReferenceId());
        e.setRecipientEmail(d.getRecipientEmail());
        e.setSubject(d.getSubject());
        e.setBody(d.getBody());
        e.setProvider(d.getProvider().name());
        e.setStatus(d.getStatus().name());
        e.setProviderMessageId(d.getProviderMessageId());
        e.setAttemptCount(d.getAttemptCount());
        e.setLastAttemptAt(d.getLastAttemptAt());
        e.setFailureReason(d.getFailureReason());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        return e;
    }
}
