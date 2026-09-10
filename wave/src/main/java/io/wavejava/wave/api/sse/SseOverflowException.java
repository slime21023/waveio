package io.wavejava.wave.api.sse;

/**
 * Signals that a slow SSE subscriber exceeded the emitter's finite queue budget.
 *
 * <p>The emitter is terminal after this exception. Its response Flow subscriber receives the
 * failure, allowing the transport to close the affected long-lived connection without retaining
 * an unbounded event queue.</p>
 */
public final class SseOverflowException extends IllegalStateException {
    private final int maximumQueuedEvents;
    private final long maximumQueuedBytes;

    SseOverflowException(int maximumQueuedEvents, long maximumQueuedBytes) {
        super("SSE subscriber exceeded queue budget of " + maximumQueuedEvents
                + " events or " + maximumQueuedBytes + " bytes");
        this.maximumQueuedEvents = maximumQueuedEvents;
        this.maximumQueuedBytes = maximumQueuedBytes;
    }

    /** Returns the configured maximum queued event count. */
    public int maximumQueuedEvents() {
        return maximumQueuedEvents;
    }

    /** Returns the configured maximum queued UTF-8 wire bytes. */
    public long maximumQueuedBytes() {
        return maximumQueuedBytes;
    }
}
