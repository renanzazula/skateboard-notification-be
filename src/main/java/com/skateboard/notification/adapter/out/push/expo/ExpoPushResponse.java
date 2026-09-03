package com.skateboard.notification.adapter.out.push.expo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Expo wraps the ticket array in a {@code data} envelope. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpoPushResponse(List<ExpoPushTicket> data) {
}
