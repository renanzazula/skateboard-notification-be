package com.skateboard.notification.application.port.out;

import java.util.UUID;

public interface ProcessedEventPort {

    /**
     * Claims an event id for processing.
     *
     * @return true if this call won the claim and the caller should process the
     *         event; false if it was already processed, in which case the
     *         caller must do nothing at all
     */
    boolean claim(UUID eventId, String eventType);
}
