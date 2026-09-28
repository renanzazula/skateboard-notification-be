package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.InboxRepositoryPort;
import com.skateboard.notification.domain.model.InboxEntry;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationType;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class InboxPersistenceAdapter implements InboxRepositoryPort {

    /**
     * Ordered to match {@code idx_user_notification_user_created_at}; id breaks
     * ties so paging is stable. Run through the EntityManager rather than a
     * repository method because the page is an arbitrary offset/limit (one row
     * past the page, to answer hasMore), which a page-number Pageable cannot
     * express. The entities carry no JPA relationship, hence the theta join.
     */
    private static final String INBOX_QUERY =
            "SELECT un, n FROM UserNotificationJpaEntity un, NotificationJpaEntity n "
                    + "WHERE n.id = un.notificationId AND un.userId = :userId AND n.tenantId = :tenantId "
                    + "ORDER BY un.createdAt DESC, un.id DESC";

    private final EntityManager entityManager;
    private final SpringUserNotificationRepository repository;

    public InboxPersistenceAdapter(EntityManager entityManager, SpringUserNotificationRepository repository) {
        this.entityManager = entityManager;
        this.repository = repository;
    }

    @Override
    public List<InboxEntry> findPage(UUID userId, UUID tenantId, int offset, int limit) {
        return entityManager.createQuery(INBOX_QUERY, Object[].class)
                .setParameter("userId", userId)
                .setParameter("tenantId", tenantId)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(row -> toDomain((UserNotificationJpaEntity) row[0], (NotificationJpaEntity) row[1]))
                .toList();
    }

    @Override
    public long countUnread(UUID userId, UUID tenantId) {
        return repository.countUnread(userId, tenantId);
    }

    @Override
    public boolean isRecipient(UUID userId, UUID tenantId, UUID notificationId) {
        return repository.isRecipient(userId, tenantId, notificationId);
    }

    @Override
    @Transactional
    public int markRead(UUID userId, UUID tenantId, UUID notificationId, Instant readAt) {
        return repository.markRead(userId, tenantId, notificationId, readAt);
    }

    @Override
    @Transactional
    public int markAllRead(UUID userId, UUID tenantId, Instant receivedUpTo, Instant readAt) {
        return repository.markAllRead(userId, tenantId, receivedUpTo, readAt);
    }

    private InboxEntry toDomain(UserNotificationJpaEntity recipient, NotificationJpaEntity entity) {
        Notification notification = Notification.reconstitute(
                entity.getId(),
                entity.getTenantId(),
                NotificationType.valueOf(entity.getType()),
                entity.getTitle(),
                entity.getBody(),
                entity.getImageUrl(),
                entity.getReferenceType(),
                entity.getReferenceId(),
                entity.getData(),
                entity.getCreatedAt());
        return new InboxEntry(notification, recipient.getReadAt());
    }
}
