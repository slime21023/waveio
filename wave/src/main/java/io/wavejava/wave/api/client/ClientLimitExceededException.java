package io.wavejava.wave.api.client;

/** Thrown when a client request, response, or admission budget would be exceeded. */
public final class ClientLimitExceededException extends IllegalStateException {
    private final String limitName;
    private final long limit;
    private final long actual;

    public ClientLimitExceededException(String limitName, long limit, long actual) {
        super("Client " + limitName + " limit of " + limit + " was exceeded by " + actual);
        this.limitName = limitName;
        this.limit = limit;
        this.actual = actual;
    }

    /** Returns the documented budget name that was exceeded. */
    public String limitName() {
        return limitName;
    }

    /** Returns the configured finite budget. */
    public long limit() {
        return limit;
    }

    /** Returns the observed size or count that exceeded the budget. */
    public long actual() {
        return actual;
    }
}
