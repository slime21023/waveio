package io.wavejava.wave.api.session;

/** Indicates that a finite session count or attribute byte budget was exceeded. */
public final class SessionLimitExceededException extends IllegalStateException {
    private final String limitName;
    private final long limit;
    private final long observed;

    /** Creates a diagnostic that exposes only finite limit metadata, never session contents. */
    public SessionLimitExceededException(String limitName, long limit, long observed) {
        super(limitName + " exceeded its configured limit of " + limit + " (observed " + observed + ')');
        this.limitName = limitName;
        this.limit = limit;
        this.observed = observed;
    }

    public String limitName() {
        return limitName;
    }

    public long limit() {
        return limit;
    }

    public long observed() {
        return observed;
    }
}
