package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.UserNotification;

import java.util.List;

public interface NotificationRepositoryPort {

    Notification save(Notification notification);

    List<UserNotification> saveRecipients(List<UserNotification> recipients);
}
