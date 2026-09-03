package com.skateboard.notification.infrastructure.messaging;

/**
 * The names shared with every producer on the platform. They live in one class
 * so a rename is a compile error here rather than a message that quietly stops
 * arriving.
 *
 * <p>skateboard-podcast-be declares the same exchange and publishes to
 * {@link #PODCAST_PUBLISHED_ROUTING_KEY}; it deliberately knows nothing about
 * the queue below.
 */
public final class EventTopology {

    /** One topic exchange for every business event on the platform. */
    public static final String EXCHANGE = "application.events";

    /** Where events that could not be processed go to be looked at. */
    public static final String DEAD_LETTER_EXCHANGE = "application.events.dlx";

    public static final String QUEUE = "notification.events";
    public static final String DEAD_LETTER_QUEUE = "notification.events.dlq";

    /**
     * Version lives in the routing key, so a v2 payload can be published
     * alongside v1 and bound separately during a migration instead of
     * breaking every consumer at once.
     */
    public static final String PODCAST_PUBLISHED_ROUTING_KEY = "podcast.published.v1";

    /**
     * Bindings are per business event rather than a blanket '#': a queue that
     * receives everything would dead-letter every event type it has no handler
     * for. Add a pattern here when a handler for it exists.
     */
    public static final String PODCAST_PUBLISHED_BINDING = "podcast.published.*";

    private EventTopology() {
    }
}
