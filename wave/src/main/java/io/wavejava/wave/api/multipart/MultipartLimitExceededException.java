package io.wavejava.wave.api.multipart;

import java.util.Locale;
import java.util.Objects;

/** Raised when parsing a multipart body would exceed one declared resource budget. */
public final class MultipartLimitExceededException extends IllegalArgumentException {
    /** A resource constrained by {@link MultipartParser.Limits}. */
    public enum Limit {
        TOTAL_BYTES,
        PARTS,
        PART_BYTES,
        HEADER_BYTES,
        TEMPORARY_DISK_BYTES
    }

    private final Limit limit;
    private final long actual;
    private final long maximum;

    MultipartLimitExceededException(Limit limit, long actual, long maximum) {
        super("Multipart " + Objects.requireNonNull(limit, "limit").name().toLowerCase(Locale.ROOT)
                + " exceeded its limit: " + actual + " > " + maximum);
        this.limit = limit;
        this.actual = actual;
        this.maximum = maximum;
    }

    /** Returns the exhausted resource budget. */
    public Limit limit() {
        return limit;
    }

    /** Returns the amount observed when parsing stopped. */
    public long actual() {
        return actual;
    }

    /** Returns the configured maximum. */
    public long maximum() {
        return maximum;
    }
}
