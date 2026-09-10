package io.wavejava.wave.api.file;

import java.util.Objects;
import java.util.Optional;

/**
 * A single HTTP {@code bytes} range request.
 *
 * <p>Multiple ranges are deliberately rejected in 0.3: multipart/byteranges would introduce a
 * second response representation and is not needed for the bounded static-file baseline. A range
 * is syntactically valid even when it cannot be satisfied by a particular resource; use
 * {@link #resolve(long)} to make that distinction.</p>
 */
public final class Range {
    private final long first;
    private final Long last;
    private final boolean suffix;

    private Range(long first, Long last, boolean suffix) {
        this.first = first;
        this.last = last;
        this.suffix = suffix;
    }

    /** Parses one {@code Range} header whose unit must be {@code bytes}. */
    public static Range parse(String header) {
        Objects.requireNonNull(header, "header");
        var trimmed = header.trim();
        var equals = trimmed.indexOf('=');
        if (equals <= 0 || !"bytes".equalsIgnoreCase(trimmed.substring(0, equals).trim())) {
            throw new IllegalArgumentException("Range unit must be bytes");
        }
        var specification = trimmed.substring(equals + 1).trim();
        if (specification.isEmpty() || specification.indexOf(',') >= 0) {
            throw new IllegalArgumentException("Only one byte range is supported");
        }
        var dash = specification.indexOf('-');
        if (dash < 0 || dash != specification.lastIndexOf('-')) {
            throw new IllegalArgumentException("Byte range must contain exactly one '-'");
        }
        var left = specification.substring(0, dash).trim();
        var right = specification.substring(dash + 1).trim();
        if (left.isEmpty() && right.isEmpty()) {
            throw new IllegalArgumentException("Byte range must name a start, end, or suffix length");
        }
        if (left.isEmpty()) {
            return new Range(parseNonNegative(right, "suffix length"), null, true);
        }

        var start = parseNonNegative(left, "range start");
        if (right.isEmpty()) {
            return new Range(start, null, false);
        }
        var end = parseNonNegative(right, "range end");
        if (end < start) {
            throw new IllegalArgumentException("Byte range end must not precede its start");
        }
        return new Range(start, end, false);
    }

    /** Resolves this range against a resource length, or returns empty when it is unsatisfiable. */
    public Optional<Resolved> resolve(long resourceLength) {
        if (resourceLength < 0) {
            throw new IllegalArgumentException("resourceLength must not be negative");
        }
        if (resourceLength == 0) {
            return Optional.empty();
        }
        if (suffix) {
            if (first == 0) {
                return Optional.empty();
            }
            var start = first >= resourceLength ? 0 : resourceLength - first;
            return Optional.of(new Resolved(start, resourceLength - 1));
        }
        if (first >= resourceLength) {
            return Optional.empty();
        }
        var end = last == null ? resourceLength - 1 : Math.min(last, resourceLength - 1);
        return end < first ? Optional.empty() : Optional.of(new Resolved(first, end));
    }

    /** Returns whether this is a suffix range such as {@code bytes=-500}. */
    public boolean isSuffix() {
        return suffix;
    }

    /** Returns the first byte position, or the suffix length for a suffix range. */
    public long first() {
        return first;
    }

    /** Returns the explicit final byte position when one was supplied. */
    public Optional<Long> last() {
        return Optional.ofNullable(last);
    }

    @Override
    public String toString() {
        if (suffix) {
            return "bytes=-" + first;
        }
        return "bytes=" + first + '-' + (last == null ? "" : last);
    }

    private static long parseNonNegative(String value, String label) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        try {
            var parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(label + " must not be negative");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value, failure);
        }
    }

    /** An inclusive, concrete byte interval for one resource. */
    public record Resolved(long start, long endInclusive) {
        public Resolved {
            if (start < 0 || endInclusive < start) {
                throw new IllegalArgumentException("Invalid resolved byte range");
            }
        }

        /** Returns the exact number of bytes represented by this interval. */
        public long length() {
            return endInclusive - start + 1;
        }
    }
}
