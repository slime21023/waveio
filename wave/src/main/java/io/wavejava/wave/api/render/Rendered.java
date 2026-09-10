package io.wavejava.wave.api.render;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * An immutable rendering result.
 *
 * <p>The media type and content length are owned by this value. Supplemental headers therefore
 * cannot include {@code Content-Type} or {@code Content-Length}; that avoids ambiguous response
 * commitment when rendering is integrated with {@code Response}.</p>
 */
public final class Rendered {
    private final byte[] bytes;
    private final MediaType mediaType;
    private final Headers headers;

    private Rendered(byte[] bytes, MediaType mediaType, Headers headers) {
        this.bytes = bytes.clone();
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.headers = validateSupplementalHeaders(headers);
    }

    /** Creates a result with no supplemental headers. */
    public static Rendered of(byte[] bytes, MediaType mediaType) {
        return new Rendered(Objects.requireNonNull(bytes, "bytes"), mediaType, Headers.empty());
    }

    /** Creates a result with safe supplemental response headers. */
    public static Rendered of(byte[] bytes, MediaType mediaType, Headers headers) {
        return new Rendered(Objects.requireNonNull(bytes, "bytes"), mediaType, Objects.requireNonNull(headers, "headers"));
    }

    /** Creates a UTF-8 plain-text result. */
    public static Rendered text(String text) {
        return text(text, StandardCharsets.UTF_8);
    }

    /** Creates a plain-text result encoded with {@code charset}. */
    public static Rendered text(String text, Charset charset) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        return of(text.getBytes(charset), MediaType.TEXT_PLAIN.withCharset(charset));
    }

    /** Returns a defensive copy of the rendered bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Returns the rendered byte length. */
    public long contentLength() {
        return bytes.length;
    }

    /** Returns the result media type. */
    public MediaType mediaType() {
        return mediaType;
    }

    /** Returns immutable supplemental response headers. */
    public Headers headers() {
        return headers;
    }

    private static Headers validateSupplementalHeaders(Headers headers) {
        Objects.requireNonNull(headers, "headers");
        if (headers.contains("Content-Type") || headers.contains("Content-Length")) {
            throw new IllegalArgumentException(
                    "Rendered supplemental headers must not contain Content-Type or Content-Length");
        }
        return headers;
    }
}
