package com.skateboard.notification.adapter.in.messaging.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * What skateboard-podcast-be says happened — the applicant's own data, no
 * recipients and no template text, matching {@link PodcastPublishedPayload}'s
 * boundary (deciding those is this service's job). See
 * skateboard-podcast-be's {@code GuestApplicationSubmissionNotifier}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuestApplicationSubmittedPayload(String applicationId,
                                                String userId,
                                                String name,
                                                String email,
                                                String message,
                                                List<String> socialLinks,
                                                String submittedAt) {
}
