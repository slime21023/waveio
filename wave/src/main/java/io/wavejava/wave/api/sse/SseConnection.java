package io.wavejava.wave.api.sse;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/** One live or reconnecting SSE client subscription. */
public interface SseConnection extends AutoCloseable {
    /** Returns the original event-stream URI. */
    URI uri();

    /** Returns whether an HTTP connection is currently open (not merely waiting to reconnect). */
    boolean isOpen();

    /** Completes normally after explicit close, or exceptionally after terminal protocol failure. */
    CompletionStage<Void> completion();

    /** Cancels any read/reconnect wait and releases the current HTTP connection. */
    @Override
    void close();
}
