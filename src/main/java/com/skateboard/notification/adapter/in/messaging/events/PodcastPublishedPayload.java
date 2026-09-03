package com.skateboard.notification.adapter.in.messaging.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * What skateboard-podcast-be says happened. Note that it describes the podcast,
 * not the notification: it carries no title text, no recipients and no push
 * concepts, because deciding those is this service's job (spec §4.2).
 *
 * @param slug the app routes by slug, not by id, so the deep link needs it —
 *             this is the one field added beyond the spec's example payload
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PodcastPublishedPayload(String podcastId,
                                       String slug,
                                       String title,
                                       String imageUrl,
                                       Instant publishedAt) {
}
