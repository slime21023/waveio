package io.wavejava.wave.api.websocket;

/** Indicates that all bounded direct-WebSocket connection admissions are in use. */
public final class WebSocketClientRejectedException extends IllegalStateException {
    private final int maximumConnections;

    public WebSocketClientRejectedException(int maximumConnections) {
        super("WebSocket client maximum connection admission of " + maximumConnections + " is exhausted");
        this.maximumConnections = maximumConnections;
    }

    /** Returns the configured finite direct-connection admission limit. */
    public int maximumConnections() {
        return maximumConnections;
    }
}
