package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.PushProvider;

import java.util.List;

/**
 * The seam that keeps Expo an infrastructure detail (spec Decision 4). Nothing
 * in {@code domain} or {@code application} imports an Expo type; swapping in
 * FCM or APNs later means adding an adapter, not touching notification logic.
 * Tests drive a fake implementation rather than sending real pushes.
 */
public interface PushNotificationProviderPort {

    PushProvider provider();

    /**
     * Sends a batch and returns one result per message, in the same order.
     * Batching is part of the contract because every real provider rate-limits
     * per request rather than per message.
     */
    List<PushResult> send(List<PushMessage> messages);

    /**
     * Asks what became of messages the provider accepted earlier.
     *
     * <p>Separate from {@link #send} because acceptance is not delivery: a
     * ticket only says the provider took the message. Implementations return
     * one receipt per id, in any order, and use
     * {@link PushReceipt.Outcome#NOT_READY} for ids the provider cannot answer
     * for yet rather than guessing.
     */
    List<PushReceipt> fetchReceipts(List<String> providerMessageIds);
}
