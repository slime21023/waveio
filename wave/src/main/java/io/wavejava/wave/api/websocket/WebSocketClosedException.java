package io.wavejava.wave.api.websocket;

/** Raised when an outbound operation races with session closure. */
public final class WebSocketClosedException extends IllegalStateException {
    public WebSocketClosedException(String message) {
        super(message);
    }
}
