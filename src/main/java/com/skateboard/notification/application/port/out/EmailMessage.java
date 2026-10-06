package com.skateboard.notification.application.port.out;

/**
 * An email as the notification layer describes it, before any provider's
 * wire format is involved — the email counterpart of {@link PushMessage}.
 *
 * @param to      destination address
 * @param subject already rendered; no templating happens below this layer
 * @param body    plain text (spec: V1 supports plain text only, no HTML)
 */
public record EmailMessage(String to, String subject, String body) {
}
