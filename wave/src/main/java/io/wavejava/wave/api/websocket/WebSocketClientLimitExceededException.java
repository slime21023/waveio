package io.wavejava.wave.api.websocket;

/** Indicates that a finite WebSocket client admission, header, or message budget was exceeded. */
public final class WebSocketClientLimitExceededException extends IllegalStateException {
    private final String limitName;
    private final long limit;
    private final long actual;

    public WebSocketClientLimitExceededException(String limitName, long limit, long actual) {
        super("WebSocket client " + limitName + " limit of " + limit + " was exceeded by " + actual);
        this.limitName = limitName;
        this.limit = limit;
        this.actual = actual;
    }

    /** Returns the documented bound that was exceeded. */
    public String limitName() {
        return limitName;
    }

    /** Returns the configured finite bound. */
    public long limit() {
        return limit;
    }

    /** Returns the observed count or byte size. */
    public long actual() {
        return actual;
    }
}
