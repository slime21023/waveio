package io.waveio.http;

import io.waveio.http.body.ResponseBody;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Flow;

/**
 * Immutable model of an outgoing HTTP response.
 *
 * <p>Constructed via fluent factory and builder methods:
 * <ul>
 *   <li>{@link #text(String)}: Plaintext 200 OK response.</li>
 *   <li>{@link #json(String)}: JSON 200 OK response with UTF-8 encoding.</li>
 *   <li>{@link #value(Object)}: Object value response serialized via configured {@link io.waveio.http.body.BodyCodec}.</li>
 *   <li>{@link #noContent()}: Empty 204 No Content response.</li>
 *   <li>{@link #status(HttpStatus)}: Fluent builder starting with any HTTP status.</li>
 * </ul>
 *
 * <p>Supports in-memory bytes, reactive streams ({@link Flow.Publisher}), and asynchronous
 * virtual-thread file delivery.
 *
 * @see Builder
 * @see ResponseBody
 */
public final class HttpResponse {
    private final HttpStatus status;
    private final HttpHeaders headers;
    private final ResponseBody body;

    private HttpResponse(HttpStatus status, HttpHeaders headers, ResponseBody body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    /**
     * Creates a {@code 200 OK} response with a UTF-8 plaintext body.
     *
     * @param text plaintext string content
     * @return a new {@code HttpResponse}
     */
    public static HttpResponse text(String text) {
        return status(HttpStatus.OK).header("content-type", "text/plain; charset=utf-8").body(text);
    }

    /**
     * Creates a {@code 200 OK} response with a JSON string body.
     *
     * @param json pre-serialized JSON string
     * @return a new {@code HttpResponse}
     */
    public static HttpResponse json(String json) {
        return status(HttpStatus.OK).header("content-type", "application/json; charset=utf-8").body(json);
    }

    /**
     * Sends an application value, encoded by the server's {@code BodyCodec} at write time.
     *
     * <p>Distinct from {@link #json(String)}, which sends a string that is already encoded. The
     * codec also supplies the content type, so this works for any format, not only JSON.
     *
     * @param value application object to be encoded by {@link io.waveio.http.body.BodyCodec}
     * @return a new {@code HttpResponse}
     */
    public static HttpResponse value(Object value) {
        return status(HttpStatus.OK).value(value);
    }

    /**
     * Creates an empty {@code 204 No Content} response without headers or payload.
     *
     * @return a new {@code HttpResponse}
     */
    public static HttpResponse noContent() {
        return status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * Starts building an HTTP response with the specified status.
     *
     * @param status target HTTP status
     * @return a new {@link Builder}
     */
    public static Builder status(HttpStatus status) {
        return new Builder(status);
    }

    /**
     * Returns the HTTP status of this response.
     *
     * @return HTTP status
     */
    public HttpStatus status() { return status; }

    /**
     * Returns the HTTP headers associated with this response.
     *
     * @return immutable headers collection
     */
    public HttpHeaders headers() { return headers; }

    /**
     * Returns the response payload.
     *
     * @return response body
     */
    public ResponseBody body() { return body; }

    /**
     * Fluent builder for constructing customized {@link HttpResponse} instances.
     */
    public static final class Builder {
        private final HttpStatus status;
        private final HttpHeaders.Builder headers = HttpHeaders.builder();

        private Builder(HttpStatus status) {
            this.status = status;
        }

        /**
         * Appends an HTTP header to the response.
         *
         * @param name header field name
         * @param value header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            headers.add(name, value);
            return this;
        }

        /**
         * Attaches a {@code Set-Cookie} header to the response.
         *
         * @param cookie the cookie to set on the client
         * @return this builder
         */
        public Builder cookie(Cookie cookie) {
            headers.add("set-cookie", cookie.toSetCookieHeader());
            return this;
        }

        /**
         * Sets a UTF-8 string payload as the response body.
         *
         * @param body raw text content
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse body(String body) {
            return body(body.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Sets plaintext content type and sets the response body.
         *
         * @param text plaintext string
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse text(String text) {
            headers.set("content-type", "text/plain; charset=utf-8");
            return body(text);
        }

        /**
         * Sets JSON content type and sets the response body.
         *
         * @param json pre-serialized JSON string
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse json(String json) {
            headers.set("content-type", "application/json; charset=utf-8");
            return body(json);
        }

        /**
         * Sets raw byte array payload as the response body.
         *
         * @param body raw byte array
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse body(byte[] body) {
            return new HttpResponse(status, headers.build(), ResponseBody.bytes(body));
        }

        /**
         * Sets a typed {@link ResponseBody} instance as the response body.
         *
         * @param body response body payload
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse body(ResponseBody body) {
            return new HttpResponse(status, headers.build(), body);
        }

        /**
         * Sends an application value encoded by the server's {@code BodyCodec}, which also
         * supplies the content type unless this builder already set one.
         *
         * @param value application value object
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse value(Object value) {
            return new HttpResponse(status, headers.build(), ResponseBody.value(value));
        }

        /**
         * Streams response bytes reactively with HTTP/1.1 chunked transfer encoding.
         *
         * @param publisher reactive publisher emitting byte buffers
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse stream(Flow.Publisher<ByteBuffer> publisher) {
            return body(ResponseBody.stream(publisher));
        }

        /**
         * Streams response bytes reactively with a known fixed content length.
         *
         * @param publisher reactive publisher emitting byte buffers
         * @param contentLength total length in bytes
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse stream(Flow.Publisher<ByteBuffer> publisher, long contentLength) {
            return body(ResponseBody.stream(publisher, contentLength));
        }

        /**
         * Streams a filesystem file on virtual threads with non-blocking backpressure.
         *
         * @param path path of the file to serve
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse file(Path path) {
            return body(ResponseBody.file(path));
        }

        /**
         * Builds an empty response with no body.
         *
         * @return a completed {@link HttpResponse}
         */
        public HttpResponse build() {
            return new HttpResponse(status, headers.build(), ResponseBody.empty());
        }
    }
}
