package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.EmailDelivery;

import java.time.Instant;
import java.util.List;

public interface EmailDeliveryRepositoryPort {

    List<EmailDelivery> saveAll(List<EmailDelivery> deliveries);

    EmailDelivery save(EmailDelivery delivery);

    /**
     * Takes ownership of a batch of emails still owed a send, marking the
     * attempt as started before returning them — the email counterpart of
     * {@link DeliveryRepositoryPort#claimRetryable}, same FOR UPDATE SKIP
     * LOCKED claim-and-return shape.
     */
    List<EmailDelivery> claimRetryable(int maxAttempts, Instant notAttemptedSince, int limit);

    long countPendingDeliveries();
}
