package io.wavejava.wave.api.websocket;

/** Raised when a WebSocket session cannot admit more bounded application data. */
public final class WebSocketLimitExceededException extends IllegalStateException {
    public WebSocketLimitExceededException(String message) {
        super(message);
    }
}
