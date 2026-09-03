package com.skateboard.notification.application.port.out;

import java.util.UUID;

public interface ProcessedEventPort {

    /**
     * Claims an event id for processing, within the caller's transaction.
     *
     * <p>Implementations must not open a transaction of their own: the claim
     * has to roll back with whatever the caller was writing, or a failure
     * mid-processing leaves the event marked done with nothing to show for it.
     *
     * @return true if this call won the claim and the caller should process the
     *         event; false if it was already processed, in which case the
     *         caller must do nothing at all
     */
    boolean claim(UUID eventId, String eventType);
}
