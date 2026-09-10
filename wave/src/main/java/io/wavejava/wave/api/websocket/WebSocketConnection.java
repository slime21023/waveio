package io.wavejava.wave.api.websocket;

import java.net.URI;
import java.util.Optional;

/**
 * One client-owned, direct WebSocket connection.
 *
 * <p>This is a {@link WebSocketSession} so client and server application code share the same
 * bounded message and Flow semantics. Its {@link #request()} is the immutable outbound HTTP
 * handshake request view, rather than an inbound server request. The connection has no pooling:
 * it retains one physical client admission until {@link #closed()} completes.</p>
 */
public interface WebSocketConnection extends WebSocketSession, AutoCloseable {
    /** Returns the direct {@code ws} URI that was successfully connected. */
    URI uri();

    /** Returns the server-selected subprotocol, when one was negotiated. */
    Optional<String> subprotocol();

    /**
     * Immediately closes the underlying transport without waiting for a close handshake.
     *
     * <p>Use {@link #close(int, String)} for a normal bounded close handshake. Calling this
     * method causes outstanding writes and inbound delivery to terminate deterministically.</p>
     */
    void abort();

    /** Equivalent to {@link #abort()}; it is provided for try-with-resources ownership. */
    @Override
    default void close() {
        abort();
    }
}
