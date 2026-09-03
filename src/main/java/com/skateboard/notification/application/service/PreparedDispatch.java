package com.skateboard.notification.application.service;

import com.skateboard.notification.domain.model.Notification;
import com.skateboard.notification.domain.model.NotificationDelivery;
import com.skateboard.notification.domain.model.NotificationDevice;

import java.util.List;

/**
 * A notification that has been persisted along with the devices it is owed to
 * and a PENDING delivery row per device — everything committed, nothing sent.
 *
 * <p>The split exists so the database work and the provider call are not in
 * the same transaction. Sending inside one would hold row locks across a
 * network call to Expo; committing first means a crash before or during the
 * send leaves a durable record of what is still owed, which is what
 * {@code RetryPendingDeliveriesService} later picks up.
 *
 * <p>{@code devices} and {@code deliveries} are positionally aligned: index i
 * of one belongs with index i of the other. {@link DispatchNotificationService}
 * relies on that to attribute each provider result.
 */
public record PreparedDispatch(Notification notification,
                                List<NotificationDevice> devices,
                                List<NotificationDelivery> deliveries) {

    public PreparedDispatch {
        if (devices.size() != deliveries.size()) {
            throw new IllegalArgumentException(
                    "devices and deliveries must align: " + devices.size() + " vs " + deliveries.size());
        }
    }

    public boolean isEmpty() {
        return devices.isEmpty();
    }
}
