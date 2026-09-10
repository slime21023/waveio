package io.wavejava.wave.api.form;

import java.util.Objects;

/** Raised when URL-encoded form input cannot be decoded safely. */
public final class MalformedFormException extends IllegalArgumentException {
    /** The malformed input category. */
    public enum Reason {
        INVALID_PERCENT_ESCAPE,
        INVALID_CHARACTER_ENCODING
    }

    private final Reason reason;
    private final int fieldIndex;
    private final int byteOffset;

    MalformedFormException(Reason reason, int fieldIndex, int byteOffset, String detail, Throwable cause) {
        super(message(reason, fieldIndex, byteOffset, detail), cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.fieldIndex = fieldIndex;
        this.byteOffset = byteOffset;
    }

    /** Returns why decoding failed. */
    public Reason reason() {
        return reason;
    }

    /** Returns the zero-based field occurrence containing the malformed bytes. */
    public int fieldIndex() {
        return fieldIndex;
    }

    /**
     * Returns the zero-based byte offset in the whole encoded body.
     *
     * <p>For an invalid character sequence, the decoder reports the start of the containing
     * component because a {@code CharacterCodingException} does not expose the precise byte.</p>
     */
    public int byteOffset() {
        return byteOffset;
    }

    private static String message(Reason reason, int fieldIndex, int byteOffset, String detail) {
        return "Malformed URL-encoded form (" + Objects.requireNonNull(reason, "reason")
                + ") at field " + fieldIndex + ", byte " + byteOffset + ": "
                + Objects.requireNonNull(detail, "detail");
    }
}
