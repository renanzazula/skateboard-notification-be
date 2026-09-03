package com.skateboard.notification.adapter.out.push.expo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * One entry of Expo's /push/send response. A ticket is an acknowledgement that
 * Expo took the message, not that a handset received it.
 *
 * @param status  "ok" or "error"
 * @param id      receipt id, present when status is "ok"
 * @param message human-readable error text
 * @param details carries {@code error}, whose value distinguishes a dead token
 *                (DeviceNotRegistered) from a transient one
 *                (MessageRateExceeded)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpoPushTicket(String status, String id, String message, Map<String, Object> details) {

    static final String STATUS_OK = "ok";

    public String errorCode() {
        if (details == null) {
            return null;
        }
        Object error = details.get("error");
        return error == null ? null : error.toString();
    }
}
