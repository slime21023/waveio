package io.wavejava.wave.testing;

import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.http.Body;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Builds an in-memory request for handler, middleware, and routing tests without opening a socket.
 *
 * <p>Each call to {@link #build()} creates a fresh {@link Body}, preserving the one-read body
 * contract across repeated test invocations.</p>
 */
public final class RequestFixture {
    private static final long DEFAULT_MAXIMUM_BODY_BYTES = 1024L * 1024L;

    private HttpMethod method = HttpMethod.GET;
    private String target = "/";
    private Headers headers = Headers.empty();
    private byte[] body = new byte[0];
    private long maximumBodyBytes = DEFAULT_MAXIMUM_BODY_BYTES;

    private RequestFixture() {
    }

    /** Starts a mutable request fixture with a GET request to {@code /}. */
    public static RequestFixture request() {
        return new RequestFixture();
    }

    /** Sets the request method. */
    public RequestFixture method(HttpMethod method) {
        this.method = Objects.requireNonNull(method, "method");
        return this;
    }

    /** Sets a standard or extension method token. */
    public RequestFixture method(String method) {
        return method(HttpMethod.of(method));
    }

    /** Sets an origin-form path, optionally including a query string. */
    public RequestFixture target(String target) {
        this.target = Objects.requireNonNull(target, "target");
        return this;
    }

    /** Replaces one request header. */
    public RequestFixture header(String name, String value) {
        headers = headers.toBuilder().set(name, value).build();
        return this;
    }

    /** Sets a UTF-8 request body. */
    public RequestFixture body(String body) {
        return body(Objects.requireNonNull(body, "body").getBytes(StandardCharsets.UTF_8));
    }

    /** Sets a defensive snapshot of request body bytes. */
    public RequestFixture body(byte[] body) {
        this.body = Objects.requireNonNull(body, "body").clone();
        return this;
    }

    /** Sets the inclusive aggregated body budget. */
    public RequestFixture maximumBodyBytes(long maximumBodyBytes) {
        if (maximumBodyBytes < 0) {
            throw new IllegalArgumentException("maximumBodyBytes must not be negative");
        }
        this.maximumBodyBytes = maximumBodyBytes;
        return this;
    }

    /** Creates a fresh immutable request. */
    public Request build() {
        return Request.builder()
                .method(method)
                .target(target)
                .headers(headers)
                .body(Body.of(body, maximumBodyBytes))
                .build();
    }

    /** Dispatches a fresh request through {@code application}. */
    public Response send(WaveApp application) {
        return TestApplication.of(Objects.requireNonNull(application, "application")).handle(build());
    }
}

