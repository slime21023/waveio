package io.wavejava.wave.api.websocket;

import io.wavejava.wave.api.http.Headers;
import java.util.Objects;

/** Indicates that a peer rejected or malformed a WebSocket HTTP upgrade response. */
public final class WebSocketHandshakeException extends RuntimeException {
    private final int status;
    private final Headers headers;

    public WebSocketHandshakeException(String message, int status, Headers headers, Throwable cause) {
        super(message, cause);
        if (status < 0 || status > 999) {
            throw new IllegalArgumentException("status must be between 0 and 999: " + status);
        }
        this.status = status;
        this.headers = Objects.requireNonNull(headers, "headers");
    }

    /** Returns the peer's HTTP status, or zero when no valid response status was received. */
    public int status() {
        return status;
    }

    /** Returns the bounded HTTP response headers observed before failure. */
    public Headers headers() {
        return headers;
    }
}
