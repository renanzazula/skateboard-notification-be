package com.skateboard.notification.adapter.out.persistence;

import com.skateboard.notification.application.port.out.NotificationRepositoryPort;
import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationType;
import com.skateboard.notification.domain.model.UserNotification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class NotificationPersistenceAdapter implements NotificationRepositoryPort {

    private final SpringNotificationRepository notificationRepository;
    private final SpringUserNotificationRepository userNotificationRepository;

    public NotificationPersistenceAdapter(SpringNotificationRepository notificationRepository,
                                           SpringUserNotificationRepository userNotificationRepository) {
        this.notificationRepository = notificationRepository;
        this.userNotificationRepository = userNotificationRepository;
    }

    @Override
    @Transactional
    public Notification save(Notification notification) {
        return toDomain(notificationRepository.save(toEntity(notification)));
    }

    @Override
    @Transactional
    public List<UserNotification> saveRecipients(List<UserNotification> recipients) {
        List<UserNotificationJpaEntity> entities = recipients.stream().map(this::toEntity).toList();
        return userNotificationRepository.saveAll(entities).stream().map(this::toDomain).toList();
    }

    private Notification toDomain(NotificationJpaEntity entity) {
        return Notification.reconstitute(
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
    }

    private NotificationJpaEntity toEntity(Notification notification) {
        NotificationJpaEntity entity = new NotificationJpaEntity();
        entity.setId(notification.getId());
        entity.setTenantId(notification.getTenantId());
        entity.setType(notification.getType().name());
        entity.setTitle(notification.getTitle());
        entity.setBody(notification.getBody());
        entity.setImageUrl(notification.getImageUrl());
        entity.setReferenceType(notification.getReferenceType());
        entity.setReferenceId(notification.getReferenceId());
        entity.setData(notification.getDataJson());
        entity.setCreatedAt(notification.getCreatedAt());
        return entity;
    }

    private UserNotification toDomain(UserNotificationJpaEntity entity) {
        return UserNotification.reconstitute(entity.getId(), entity.getNotificationId(), entity.getUserId(),
                entity.getReadAt(), entity.getCreatedAt());
    }

    private UserNotificationJpaEntity toEntity(UserNotification userNotification) {
        UserNotificationJpaEntity entity = new UserNotificationJpaEntity();
        entity.setId(userNotification.getId());
        entity.setNotificationId(userNotification.getNotificationId());
        entity.setUserId(userNotification.getUserId());
        entity.setReadAt(userNotification.getReadAt());
        entity.setCreatedAt(userNotification.getCreatedAt());
        return entity;
    }
}
