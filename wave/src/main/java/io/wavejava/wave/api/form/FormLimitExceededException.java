package io.wavejava.wave.api.form;

import java.util.Objects;

/** Raised when a URL-encoded form exceeds one configured parser limit. */
public final class FormLimitExceededException extends IllegalArgumentException {
    /** The bounded resource that was exceeded. */
    public enum Limit {
        BODY_BYTES,
        FIELDS,
        FIELD_NAME_CHARACTERS,
        FIELD_VALUE_CHARACTERS
    }

    private final Limit limit;
    private final long actual;
    private final long maximum;

    FormLimitExceededException(Limit limit, long actual, long maximum) {
        super("Form " + Objects.requireNonNull(limit, "limit").name().toLowerCase(java.util.Locale.ROOT)
                + " exceeded its limit: " + actual + " > " + maximum);
        this.limit = limit;
        this.actual = actual;
        this.maximum = maximum;
    }

    /** Returns the exhausted resource limit. */
    public Limit limit() {
        return limit;
    }

    /** Returns the observed amount. */
    public long actual() {
        return actual;
    }

    /** Returns the configured maximum. */
    public long maximum() {
        return maximum;
    }
}
