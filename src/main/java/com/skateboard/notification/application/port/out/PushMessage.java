package com.skateboard.notification.application.port.out;

import java.util.Map;

/**
 * A push as the notification layer describes it, before any provider's wire
 * format is involved.
 *
 * @param pushToken the destination credential
 * @param title     human-readable heading
 * @param body      human-readable body
 * @param data      machine-readable navigation metadata the app switches on
 *                  (spec §20) — semantic targets, never a backend-built URL
 */
public record PushMessage(String pushToken, String title, String body, Map<String, String> data) {
}
