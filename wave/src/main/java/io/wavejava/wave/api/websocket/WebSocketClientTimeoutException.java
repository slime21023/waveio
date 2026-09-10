package io.wavejava.wave.api.websocket;

import java.time.Duration;
import java.util.Objects;

/** Indicates that direct WebSocket connection or handshake establishment exceeded its deadline. */
public final class WebSocketClientTimeoutException extends RuntimeException {
    private final Duration timeout;

    public WebSocketClientTimeoutException(Duration timeout, Throwable cause) {
        super("WebSocket client establishment exceeded its timeout of " + Objects.requireNonNull(timeout, "timeout"), cause);
        this.timeout = timeout;
    }

    /** Returns the effective finite establishment timeout. */
    public Duration timeout() {
        return timeout;
    }
}
