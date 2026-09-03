package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.NotificationDelivery;

import java.time.Instant;
import java.util.List;

public interface DeliveryRepositoryPort {

    List<NotificationDelivery> saveAll(List<NotificationDelivery> deliveries);

    NotificationDelivery save(NotificationDelivery delivery);

    /**
     * Takes ownership of a batch of deliveries still owed a send, marking the
     * attempt as started before returning them.
     *
     * <p>Claiming and returning in one step is what keeps two instances from
     * sending the same push twice: the rows are locked while selected, and the
     * attempt they are stamped with moves them out of the next poll's window.
     *
     * @param maxAttempts     deliveries at or above this many attempts are given up on
     * @param notAttemptedSince only consider deliveries last tried before this instant
     */
    List<NotificationDelivery> claimRetryable(int maxAttempts, Instant notAttemptedSince, int limit);
}
