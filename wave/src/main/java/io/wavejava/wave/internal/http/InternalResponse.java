package io.wavejava.wave.internal.http;

import io.wavejava.wave.api.http.*;
import io.wavejava.wave.api.websocket.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * The mutable response builder for one request invocation.
 *
 * <p>Metadata may be changed while the response is {@link ResponseState#OPEN}. A writer such as
 * {@link #text(String)}, {@link #bytes(byte[], MediaType)}, {@link #json(Object)}, or {@link
 * #problem(Problem)} commits it atomically. After commitment no headers, status, or body can change.</p>
 */
public final class InternalResponse extends Response {
    private ResponseState state = ResponseState.OPEN;
    private int status = 200;
    private Headers headers = Headers.empty();
    private ResponseData body;

    public InternalResponse() {
    }

    /** Returns the current lifecycle state. */
    public synchronized ResponseState state() {
        return state;
    }

    /** Returns whether this response cannot be changed further. */
    public synchronized boolean isCommitted() {
        return state != ResponseState.OPEN;
    }

    /** Returns the selected HTTP status code. */
    public synchronized int status() {
        return status;
    }

    /** Sets the status code. A body-forbidden status commits an empty response immediately. */
    public synchronized Response status(int status) {
        ensureOpen();
        this.status = validateStatus(status);
        if (forbidsBody(status)) {
            commitInternal();
        }
        return this;
    }

    /** Replaces all values for a response header. */
    public synchronized Response header(String name, String value) {
        ensureOpen();
        headers = headers.toBuilder().set(name, value).build();
        return this;
    }

    /** Appends a value for a response header. */
    public synchronized Response addHeader(String name, String value) {
        ensureOpen();
        headers = headers.toBuilder().add(name, value).build();
        return this;
    }

    /** Removes all values for a response header. */
    public synchronized Response removeHeader(String name) {
        ensureOpen();
        headers = headers.toBuilder().remove(name).build();
        return this;
    }

    /** Adds a {@code Set-Cookie} response header. */
    public synchronized Response cookie(Cookie cookie) {
        return addHeader("Set-Cookie", Objects.requireNonNull(cookie, "cookie").toSetCookieHeader());
    }

    /** Returns an immutable snapshot of response headers. */
    public synchronized Headers headers() {
        return headers;
    }

    /** Returns the internal committed representation for the transport boundary. */
    synchronized ResponseData data() {
        return body;
    }

    /** Writes UTF-8 plain text and commits the response. */
    public synchronized Response text(String text) {
        Objects.requireNonNull(text, "text");
        return write(ResponseData.bytes(text.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN_UTF_8), false);
    }

    /** Writes bytes with {@code mediaType} and commits the response. */
    public synchronized Response bytes(byte[] bytes, MediaType mediaType) {
        return write(ResponseData.bytes(bytes, mediaType), true);
    }

    /**
     * Writes a Flow-controlled binary response and commits this response.
     *
     * <p>The HTTP/1.1 adapter owns subscription demand and requests one buffer only when the
     * channel is writable and its configured outbound-byte budget has room. The publisher's
     * buffers are copied at the transport boundary, so application code never transfers Netty
     * buffer ownership. Each emitted buffer must have at least one remaining byte; an empty item
     * is rejected to prevent an unbounded zero-byte demand loop. A stream has unknown length and therefore cannot declare either
     * {@code Content-Length} or {@code Transfer-Encoding}; the transport selects chunked framing.
     * Set {@code Content-Type} before this call to override the default binary media type.</p>
     */
    public synchronized Response stream(Flow.Publisher<ByteBuffer> publisher) {
        ensureOpen();
        if (forbidsBody(status)) {
            throw new IllegalStateException("HTTP status " + status + " does not permit a response body");
        }
        if (headers.contains("Content-Length")) {
            throw new IllegalStateException("A streaming response must not declare Content-Length");
        }
        if (headers.contains("Transfer-Encoding")) {
            throw new IllegalStateException("A streaming response must not declare Transfer-Encoding");
        }
        body = ResponseData.stream(Objects.requireNonNull(publisher, "publisher"));
        if (!headers.contains("Content-Type")) {
            headers = headers.toBuilder().set("Content-Type", MediaType.APPLICATION_OCTET_STREAM.toString()).build();
        }
        commitInternal();
        return this;
    }

    /**
     * Commits an HTTP-to-WebSocket upgrade decision for this request.
     *
     * <p>The method records an application-level endpoint only. The server transport validates
     * the HTTP/1.1 upgrade headers and writes the ordered {@code 101} response after normal
     * middleware and routing complete. An invalid wire upgrade therefore becomes an HTTP error,
     * never a partially initialized WebSocket session.</p>
     */
    public synchronized Response upgrade(ResponseUpgrade upgrade) {
        ensureOpen();
        if (status != 200) {
            throw new IllegalStateException("A WebSocket upgrade must use the default response status");
        }
        if (headers.contains("Content-Length") || headers.contains("Transfer-Encoding")) {
            throw new IllegalStateException("A WebSocket upgrade must not declare an HTTP response body");
        }
        if (!(Objects.requireNonNull(upgrade, "upgrade") instanceof WebSocket endpoint)) {
            throw new IllegalArgumentException("Unsupported response upgrade: " + upgrade.getClass().getName());
        }
        body = ResponseData.webSocket(endpoint);
        commitInternal();
        return this;
    }

    /** Sets a logical JSON value and commits the response. */
    public synchronized Response json(Object value) {
        return write(ResponseData.json(value), false);
    }

    /** Sets a problem response, adopts its status, and commits the response. */
    public synchronized Response problem(Problem problem) {
        ensureOpen();
        var data = ResponseData.problem(problem);
        status = problem.status();
        return write(data, false);
    }

    /** Writes an empty response and commits it. */
    public synchronized Response empty() {
        ensureOpen();
        commitInternal();
        return this;
    }

    /** Commits the response without a body. Equivalent to {@link #empty()}. */
    public synchronized Response commit() {
        return empty();
    }

    /** Sets a redirect status and Location header, then commits an empty response. */
    public synchronized Response redirect(int status, String location) {
        ensureOpen();
        if (status < 300 || status > 399 || status == 304) {
            throw new IllegalArgumentException("Redirect status must be a 3xx status other than 304: " + status);
        }
        validateHeaderValue(Objects.requireNonNull(location, "location"));
        if (location.isBlank()) {
            throw new IllegalArgumentException("Redirect location must not be blank");
        }
        this.status = status;
        headers = headers.toBuilder().set("Location", location).build();
        commitInternal();
        return this;
    }

    /** Marks a committed response as successfully written by the transport. */
    public synchronized Response complete() {
        if (state == ResponseState.COMPLETED) {
            return this;
        }
        if (state != ResponseState.COMMITTED) {
            throw new IllegalStateException("Only a committed response can be completed; current state is " + state);
        }
        state = ResponseState.COMPLETED;
        return this;
    }

    /** Marks this response unusable after a transport failure or cancellation. */
    public synchronized Response abort() {
        if (state == ResponseState.COMPLETED) {
            throw new IllegalStateException("A completed response cannot be aborted");
        }
        state = ResponseState.ABORTED;
        return this;
    }

    private Response write(ResponseData data, boolean forceMediaType) {
        ensureOpen();
        if (forbidsBody(status)) {
            throw new IllegalStateException("HTTP status " + status + " does not permit a response body");
        }
        body = data;
        if (forceMediaType || !headers.contains("Content-Type")) {
            headers = headers.toBuilder().set("Content-Type", data.mediaType().toString()).build();
        }
        data.contentLength().ifPresent(length ->
                headers = headers.toBuilder().set("Content-Length", Long.toString(length)).build());
        commitInternal();
        return this;
    }

    private void commitInternal() {
        state = ResponseState.COMMITTED;
    }

    private void ensureOpen() {
        if (state != ResponseState.OPEN) {
            throw new IllegalStateException("Response is " + state + " and can no longer be changed");
        }
    }

    private static boolean forbidsBody(int status) {
        return status >= 100 && status < 200 || status == 204 || status == 304;
    }

    private static int validateStatus(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP status must be between 100 and 599: " + status);
        }
        return status;
    }

    private static String validateHeaderValue(String value) {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == 0 || character == 0x7f
                    || (character < 0x20 && character != '\t')) {
                throw new IllegalArgumentException("HTTP header value contains an unsafe control character");
            }
        }
        return value;
    }
}

