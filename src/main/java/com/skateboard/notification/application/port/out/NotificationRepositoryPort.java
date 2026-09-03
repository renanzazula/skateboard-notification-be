package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.UserNotification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepositoryPort {

    Notification save(Notification notification);

    Optional<Notification> findById(UUID id);

    List<UserNotification> saveRecipients(List<UserNotification> recipients);
}
