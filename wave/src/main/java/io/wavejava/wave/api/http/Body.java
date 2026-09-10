package io.wavejava.wave.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A bounded, aggregated request body that can be consumed exactly once.
 *
 * <p>The inbound adapter must construct this type only after enforcing its byte budget. The content
 * is copied on input and output, so no transport buffer ownership crosses the public API boundary.</p>
 */
public final class Body {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final byte[] content;
    private final long maximumBytes;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private Body(byte[] content, long maximumBytes) {
        this.content = content;
        this.maximumBytes = maximumBytes;
    }

    /** Creates a bounded body from an owned snapshot of {@code content}. */
    public static Body of(byte[] content, long maximumBytes) {
        Objects.requireNonNull(content, "content");
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        if (content.length > maximumBytes) {
            throw new BodyTooLargeException(content.length, maximumBytes);
        }
        return new Body(content.clone(), maximumBytes);
    }

    /** Creates an empty body with a zero-byte budget. */
    public static Body empty() {
        return new Body(new byte[0], 0);
    }

    /** Creates a UTF-8 encoded bounded body. */
    public static Body utf8(String text, long maximumBytes) {
        Objects.requireNonNull(text, "text");
        return of(text.getBytes(StandardCharsets.UTF_8), maximumBytes);
    }

    /** Returns the received content length without consuming the body. */
    public long length() {
        return content.length;
    }

    /** Returns the configured aggregation budget. */
    public long maximumBytes() {
        return maximumBytes;
    }

    /** Returns whether any reader has already consumed this body. */
    public boolean isConsumed() {
        return consumed.get();
    }

    /** Returns a defensive copy of the body bytes and marks this body consumed. */
    public byte[] bytes() {
        return take();
    }

    /** Decodes the body as UTF-8 and marks this body consumed. */
    public String text() {
        return text(StandardCharsets.UTF_8);
    }

    /** Decodes the body using {@code charset} and marks this body consumed. */
    public String text(Charset charset) {
        return new String(take(), Objects.requireNonNull(charset, "charset"));
    }

    /**
     * Deserializes this body as JSON and marks it consumed.
     *
     * @throws IllegalArgumentException when the body is not valid JSON for {@code type}
     */
    public <T> T json(Class<T> type) {
        Objects.requireNonNull(type, "type");
        try {
            return JSON.readValue(take(), type);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Request body is not valid JSON for " + type.getName(), failure);
        }
    }

    private byte[] take() {
        if (!consumed.compareAndSet(false, true)) {
            throw new BodyAlreadyConsumedException();
        }
        return content.clone();
    }
}
