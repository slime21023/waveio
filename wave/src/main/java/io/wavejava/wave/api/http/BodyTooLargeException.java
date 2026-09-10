package io.wavejava.wave.api.http;

/** Thrown when an aggregated request body exceeds its configured byte budget. */
public final class BodyTooLargeException extends IllegalArgumentException {
    private final long actualBytes;
    private final long maximumBytes;

    public BodyTooLargeException(long actualBytes, long maximumBytes) {
        super("Request body size " + actualBytes + " bytes exceeds the configured maximum of " + maximumBytes + " bytes");
        this.actualBytes = actualBytes;
        this.maximumBytes = maximumBytes;
    }

    /** Returns the observed body size. */
    public long actualBytes() {
        return actualBytes;
    }

    /** Returns the configured maximum size. */
    public long maximumBytes() {
        return maximumBytes;
    }
}
