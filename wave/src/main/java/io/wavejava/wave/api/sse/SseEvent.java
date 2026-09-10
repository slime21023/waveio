package io.wavejava.wave.api.sse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable Server-Sent Event encoded according to the HTML event-stream wire format.
 *
 * <p>Event names and identifiers are single-line protocol fields. Data and comments may contain
 * line breaks; each logical line is encoded as its own {@code data:} or {@code :} field. All
 * output is UTF-8 and always ends in the blank line that dispatches an SSE event block.</p>
 */
public final class SseEvent {
    private final String data;
    private final String event;
    private final String id;
    private final Duration retry;
    private final String comment;

    private SseEvent(Builder builder) {
        data = builder.data;
        event = builder.event;
        id = builder.id;
        retry = builder.retry;
        comment = builder.comment;
        validate();
    }

    /** Starts a builder for one event block. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a data event with the supplied UTF-8 payload. */
    public static SseEvent data(String data) {
        return builder().data(data).build();
    }

    /** Creates a comment-only event block. Comments are not delivered as browser message events. */
    public static SseEvent comment(String comment) {
        return builder().comment(comment).build();
    }

    /** Creates the conventional empty comment heartbeat ({@code :\n\n}). */
    public static SseEvent heartbeat() {
        return comment("");
    }

    /** Returns the data value, if this event carries one. */
    public Optional<String> data() {
        return Optional.ofNullable(data);
    }

    /** Returns the optional event type. */
    public Optional<String> event() {
        return Optional.ofNullable(event);
    }

    /**
     * Returns the optional last-event ID. An empty present value is valid and resets a client's
     * remembered ID; callers can distinguish it from absence through {@link Optional#isPresent()}.
     */
    public Optional<String> id() {
        return Optional.ofNullable(id);
    }

    /** Returns the optional client reconnection delay. */
    public Optional<Duration> retry() {
        return Optional.ofNullable(retry);
    }

    /** Returns the optional comment payload. */
    public Optional<String> comment() {
        return Optional.ofNullable(comment);
    }

    /**
     * Encodes this event as a fresh UTF-8 byte array suitable for a {@code text/event-stream}
     * response.
     */
    public byte[] encode() {
        var wire = new StringBuilder();
        if (comment != null) {
            appendMultilineComment(wire, comment);
        }
        if (event != null) {
            appendField(wire, "event", event);
        }
        if (id != null) {
            appendField(wire, "id", id);
        }
        if (retry != null) {
            appendField(wire, "retry", Long.toString(retry.toMillis()));
        }
        if (data != null) {
            appendMultilineField(wire, "data", data);
        }
        wire.append('\n');
        return wire.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void validate() {
        if (data == null && event == null && id == null && retry == null && comment == null) {
            throw new IllegalArgumentException("An SSE event must contain data, metadata, or a comment");
        }
        if (event != null) {
            if (event.isEmpty()) {
                throw new IllegalArgumentException("SSE event name must not be empty");
            }
            validateSingleLine(event, "event name");
        }
        if (id != null) {
            validateSingleLine(id, "event ID");
        }
        if (data != null) {
            validateNoNul(data, "event data");
        }
        if (comment != null) {
            validateNoNul(comment, "event comment");
        }
        if (retry != null) {
            if (retry.isNegative()) {
                throw new IllegalArgumentException("SSE retry delay must not be negative");
            }
            try {
                retry.toMillis();
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException("SSE retry delay is too large to encode in milliseconds", failure);
            }
        }
    }

    private static void validateSingleLine(String value, String name) {
        validateNoNul(value, name);
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("SSE " + name + " must not contain a line break");
        }
    }

    private static void validateNoNul(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("SSE " + name + " must not contain NUL");
        }
    }

    private static void appendMultilineComment(StringBuilder target, String value) {
        forEachLine(value, line -> {
            target.append(':');
            if (!line.isEmpty()) {
                target.append(' ').append(line);
            }
            target.append('\n');
        });
    }

    private static void appendMultilineField(StringBuilder target, String name, String value) {
        forEachLine(value, line -> appendField(target, name, line));
    }

    private static void appendField(StringBuilder target, String name, String value) {
        target.append(name).append(':');
        if (!value.isEmpty()) {
            // The SSE parser strips exactly one optional leading space after ':'. Including one
            // here preserves a user value which itself begins with whitespace.
            target.append(' ').append(value);
        }
        target.append('\n');
    }

    private static void forEachLine(String value, java.util.function.Consumer<String> consumer) {
        var start = 0;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character != '\r' && character != '\n') {
                continue;
            }
            consumer.accept(value.substring(start, index));
            if (character == '\r' && index + 1 < value.length() && value.charAt(index + 1) == '\n') {
                index++;
            }
            start = index + 1;
        }
        consumer.accept(value.substring(start));
    }

    /** Builder for immutable {@link SseEvent} values. */
    public static final class Builder {
        private String data;
        private String event;
        private String id;
        private Duration retry;
        private String comment;

        private Builder() {
        }

        /** Sets the event data. Newlines are represented as separate {@code data:} wire fields. */
        public Builder data(String data) {
            this.data = Objects.requireNonNull(data, "data");
            return this;
        }

        /** Sets a non-empty, single-line event type. */
        public Builder event(String event) {
            this.event = Objects.requireNonNull(event, "event");
            return this;
        }

        /** Sets a single-line event ID. The empty ID is valid and resets client state. */
        public Builder id(String id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        /** Sets the client reconnect delay; zero is valid and negatives are rejected on build. */
        public Builder retry(Duration retry) {
            this.retry = Objects.requireNonNull(retry, "retry");
            return this;
        }

        /** Sets a comment; newlines are represented as separate comment lines. */
        public Builder comment(String comment) {
            this.comment = Objects.requireNonNull(comment, "comment");
            return this;
        }

        /** Builds the immutable event after validating all protocol fields. */
        public SseEvent build() {
            return new SseEvent(this);
        }
    }
}
