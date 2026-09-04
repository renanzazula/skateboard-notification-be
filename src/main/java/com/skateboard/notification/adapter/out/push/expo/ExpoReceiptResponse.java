package com.skateboard.notification.adapter.out.push.expo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Expo's /push/getReceipts response: a map of ticket id to receipt, wrapped in
 * the same {@code data} envelope /push/send uses. An id Expo has no receipt for
 * is simply absent from the map rather than reported as an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpoReceiptResponse(Map<String, ExpoPushTicket> data) {
}
