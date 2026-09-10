package io.wavejava.wave.api.sse;

import java.net.URI;

/**
 * A bounded HTTP/1.1 Server-Sent Events client.
 *
 * <p>Create a client through {@link io.wavejava.wave.Wave#sseClient()} so the public API does not
 * expose its transport implementation. Listener callbacks never run on a transport event loop.</p>
 */
public interface SseClient extends AutoCloseable {
    /** Default maximum bytes in the response status line and all response headers. */
    int DEFAULT_MAXIMUM_HEADER_BYTES = SseClientOptions.DEFAULT_MAXIMUM_HEADER_BYTES;

    /** Default maximum response header field count. */
    int DEFAULT_MAXIMUM_HEADER_COUNT = SseClientOptions.DEFAULT_MAXIMUM_HEADER_COUNT;

    /** Default bound for one UTF-8 event-stream line. */
    int DEFAULT_MAXIMUM_LINE_BYTES = SseClientOptions.DEFAULT_MAXIMUM_LINE_BYTES;

    /** Default bound for one accumulated event block. */
    int DEFAULT_MAXIMUM_EVENT_BYTES = SseClientOptions.DEFAULT_MAXIMUM_EVENT_BYTES;

    /** Default finite budget for reconnect attempts after the first connection. */
    int DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS = SseClientOptions.DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS;

    /** Default cap for live or reconnecting client subscriptions owned by one client. */
    int DEFAULT_MAXIMUM_CONNECTIONS = SseClientOptions.DEFAULT_MAXIMUM_CONNECTIONS;

    /** Opens an asynchronous SSE connection. */
    SseConnection connect(URI uri, SseListener listener);

    /** Returns whether this client has been closed. */
    boolean isClosed();

    /** Closes all owned SSE subscriptions and prevents new ones. */
    @Override
    void close();
}
