package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.NotificationDelivery;

import java.util.List;

public interface DeliveryRepositoryPort {

    List<NotificationDelivery> saveAll(List<NotificationDelivery> deliveries);

    NotificationDelivery save(NotificationDelivery delivery);
}
