package com.skateboard.notification.application.port.out;

import com.skateboard.notification.domain.model.EmailProvider;

import java.util.List;

/**
 * The seam that keeps Brevo an infrastructure detail, the email counterpart
 * of {@link PushNotificationProviderPort}. Nothing in {@code domain} or
 * {@code application} imports a Brevo type; swapping in Resend or SES later
 * means adding an adapter, not touching notification logic.
 */
public interface EmailProviderPort {

    EmailProvider provider();

    /**
     * Sends a batch and returns one result per message, in the same order.
     * Brevo's transactional API takes one message per request, so a batch
     * here is a loop, not a single call — but the contract stays
     * batch-shaped so the caller doesn't need to know that.
     */
    List<EmailResult> send(List<EmailMessage> messages);
}
