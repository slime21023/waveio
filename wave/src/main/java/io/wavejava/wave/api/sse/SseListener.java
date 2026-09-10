package io.wavejava.wave.api.sse;

import java.time.Duration;

/** Receives parsed events from one {@link SseClient} connection. */
@FunctionalInterface
public interface SseListener {
    /** Receives one dispatched SSE data event. This callback runs on a client-owned virtual thread. */
    void onEvent(SseEvent event);

    /** Called after a successful HTTP event-stream response has been validated. */
    default void onOpen(SseResponseInfo response) {
    }

    /** Receives one comment line, including heartbeat comments. */
    default void onComment(String comment) {
    }

    /** Called before a reconnect attempt after the supplied finite delay. */
    default void onReconnect(Duration delay, int attempt) {
    }

    /** Called once after explicit close or a peer EOF that cannot reconnect further. */
    default void onClosed() {
    }

    /** Called once for a terminal protocol, resource-limit, or listener failure. */
    default void onFailure(Throwable failure) {
    }
}

