package io.wavejava.wave.internal.http;

import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.websocket.WebSocket;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Flow;

/**
 * An immutable response payload snapshot used by the response builder and transport preparation.
 *
 * <p>Bytes are used directly by the HTTP/1.1 adapter. JSON and problem values remain logical values
 * until the configured renderer serializes them; this keeps a codec implementation out of the HTTP
 * value API. Application code should normally use the explicit writer methods on {@link Response}
 * rather than inspect this transport representation.</p>
 */
public final class ResponseData {
    /** The representation held by this body. */
    public enum Kind {
        BYTES,
        JSON,
        PROBLEM,
        STREAM,
        WEBSOCKET
    }

    private final Kind kind;
    private final MediaType mediaType;
    private final byte[] bytes;
    private final Object value;
    private final Flow.Publisher<ByteBuffer> stream;
    private final WebSocket webSocket;

    private ResponseData(
            Kind kind,
            MediaType mediaType,
            byte[] bytes,
            Object value,
            Flow.Publisher<ByteBuffer> stream,
            WebSocket webSocket) {
        this.kind = kind;
        this.mediaType = mediaType;
        this.bytes = bytes;
        this.value = value;
        this.stream = stream;
        this.webSocket = webSocket;
    }

    /** Creates a binary body from a defensive copy of {@code bytes}. */
    static ResponseData bytes(byte[] bytes, MediaType mediaType) {
        return new ResponseData(Kind.BYTES, Objects.requireNonNull(mediaType, "mediaType"),
                Objects.requireNonNull(bytes, "bytes").clone(), null, null, null);
    }

    /** Creates a logical JSON body. {@code value} may be {@code null} to represent JSON null. */
    static ResponseData json(Object value) {
        return new ResponseData(Kind.JSON, MediaType.APPLICATION_JSON, null, value, null, null);
    }

    /** Creates a logical RFC 9457 problem body. */
    static ResponseData problem(Problem problem) {
        return new ResponseData(Kind.PROBLEM, MediaType.APPLICATION_PROBLEM_JSON, null,
                Objects.requireNonNull(problem, "problem"), null, null);
    }

    /** Creates a response body delivered by the HTTP transport under Flow backpressure. */
    static ResponseData stream(Flow.Publisher<ByteBuffer> publisher) {
        return new ResponseData(
                Kind.STREAM,
                MediaType.APPLICATION_OCTET_STREAM,
                null,
                null,
                Objects.requireNonNull(publisher, "publisher"),
                null);
    }

    /** Creates an internal HTTP upgrade signal for one WebSocket endpoint. */
    static ResponseData webSocket(WebSocket endpoint) {
        return new ResponseData(
                Kind.WEBSOCKET,
                MediaType.APPLICATION_OCTET_STREAM,
                null,
                null,
                null,
                Objects.requireNonNull(endpoint, "endpoint"));
    }

    public Kind kind() {
        return kind;
    }

    public MediaType mediaType() {
        return mediaType;
    }

    /** Returns a defensive byte copy for binary bodies, or empty for logical bodies. */
    public Optional<byte[]> byteContent() {
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

    /** Returns the logical JSON/problem value; binary bodies return {@code null}. */
    public Object value() {
        return value;
    }

    /** Returns the Flow body source for streaming bodies, or empty for materialized bodies. */
    public Optional<Flow.Publisher<ByteBuffer>> stream() {
        return Optional.ofNullable(stream);
    }

    /** Returns the selected WebSocket endpoint for an upgrade response, if any. */
    public Optional<WebSocket> webSocket() {
        return Optional.ofNullable(webSocket);
    }

    /** Returns a known byte count for binary bodies. */
    public OptionalLong contentLength() {
        return bytes == null ? OptionalLong.empty() : OptionalLong.of(bytes.length);
    }
}

