package io.wavejava.wave.api.client;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Deadline;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.MediaType;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable outbound HTTP request with a repeatable, bounded byte body.
 *
 * <p>Streaming request bodies are intentionally outside this byte-body contract. The body is
 * copied at assembly and each transport attempt receives a fresh byte publisher, making a request
 * replayable when {@link #retrySafe()} allows it.</p>
 */
public final class ClientRequest {
    private final URI uri;
    private final HttpMethod method;
    private final Headers headers;
    private final byte[] body;
    private final Duration timeout;
    private final Deadline deadline;
    private final CancellationToken cancellationToken;
    private final boolean retrySafe;

    private ClientRequest(Builder builder) {
        uri = validateUri(builder.uri);
        method = Objects.requireNonNull(builder.method, "method");
        headers = Objects.requireNonNull(builder.headers, "headers");
        body = builder.body.clone();
        timeout = builder.timeout;
        deadline = builder.deadline;
        cancellationToken = builder.cancellationToken;
        retrySafe = builder.retrySafe == null ? RetryPolicy.isIdempotent(method) : builder.retrySafe;
    }

    /** Starts a request builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates a GET request for an absolute HTTP(S) URI. */
    public static ClientRequest get(URI uri) {
        return builder().uri(uri).method(HttpMethod.GET).build();
    }

    /** Returns the absolute HTTP(S) target URI. */
    public URI uri() {
        return uri;
    }

    /** Returns the outbound method token. */
    public HttpMethod method() {
        return method;
    }

    /** Returns immutable request headers. */
    public Headers headers() {
        return headers;
    }

    /** Returns a defensive copy of the repeatable byte body. */
    public byte[] body() {
        return body.clone();
    }

    /** Returns the exact byte-body size before client-pool admission. */
    public int bodyLength() {
        return body.length;
    }

    /** Returns an optional end-to-end timeout override. */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /** Returns an optional absolute deadline. */
    public Optional<Deadline> deadline() {
        return Optional.ofNullable(deadline);
    }

    /** Returns an optional cooperative cancellation signal. */
    public Optional<CancellationToken> cancellationToken() {
        return Optional.ofNullable(cancellationToken);
    }

    /** Returns whether this request is explicitly safe for retry by a configured retry policy. */
    public boolean retrySafe() {
        return retrySafe;
    }

    /** Returns a builder pre-populated from this immutable request. */
    public Builder toBuilder() {
        var builder = builder()
                .uri(uri)
                .method(method)
                .headers(headers)
                .body(body)
                .retrySafe(retrySafe);
        if (timeout != null) {
            builder.timeout(timeout);
        }
        if (deadline != null) {
            builder.deadline(deadline);
        }
        if (cancellationToken != null) {
            builder.cancellationToken(cancellationToken);
        }
        return builder;
    }

    private static URI validateUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || uri.getHost() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Client request URI must be an absolute http(s) authority URI without user info or fragment: " + uri);
        }
        if (uri.getPort() > 65_535) {
            throw new IllegalArgumentException("Client request URI port must be at most 65535: " + uri);
        }
        return URI.create(uri.toString());
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero: " + timeout);
        }
        return timeout;
    }

    /** Builder for an immutable {@link ClientRequest}. */
    public static final class Builder {
        private URI uri;
        private HttpMethod method = HttpMethod.GET;
        private Headers headers = Headers.empty();
        private byte[] body = new byte[0];
        private Duration timeout;
        private Deadline deadline;
        private CancellationToken cancellationToken;
        private Boolean retrySafe;

        private Builder() {
        }

        /** Sets the absolute HTTP(S) target URI. */
        public Builder uri(URI uri) {
            this.uri = validateUri(uri);
            return this;
        }

        /** Sets the outbound HTTP method. */
        public Builder method(HttpMethod method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        /** Sets the outbound HTTP method from a valid token. */
        public Builder method(String method) {
            return method(HttpMethod.of(method));
        }

        /** Replaces all request headers. */
        public Builder headers(Headers headers) {
            this.headers = Objects.requireNonNull(headers, "headers");
            return this;
        }

        /** Replaces all values for one request header. */
        public Builder header(String name, String value) {
            headers = headers.toBuilder().set(name, value).build();
            return this;
        }

        /** Appends one request header value. */
        public Builder addHeader(String name, String value) {
            headers = headers.toBuilder().add(name, value).build();
            return this;
        }

        /** Sets a repeatable byte body using a defensive copy. */
        public Builder body(byte[] body) {
            this.body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
            return this;
        }

        /** Sets a byte body and its Content-Type header. */
        public Builder body(byte[] body, MediaType mediaType) {
            return body(body).header("Content-Type", Objects.requireNonNull(mediaType, "mediaType").toString());
        }

        /** Sets a text body encoded with UTF-8 and a corresponding Content-Type header. */
        public Builder text(String text) {
            return text(text, MediaType.TEXT_PLAIN_UTF_8);
        }

        /** Sets a text body using the charset declared by {@code mediaType}, or UTF-8 when absent. */
        public Builder text(String text, MediaType mediaType) {
            Objects.requireNonNull(text, "text");
            var type = Objects.requireNonNull(mediaType, "mediaType");
            Charset charset = type.charset().orElse(StandardCharsets.UTF_8);
            return body(text.getBytes(charset), type.charset().isPresent() ? type : type.withCharset(charset));
        }

        /** Sets a finite end-to-end timeout override for this logical exchange. */
        public Builder timeout(Duration timeout) {
            this.timeout = requirePositive(timeout);
            return this;
        }

        /** Sets an absolute deadline for this logical exchange. */
        public Builder deadline(Deadline deadline) {
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            return this;
        }

        /** Uses a cooperative cancellation signal for this logical exchange. */
        public Builder cancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
            return this;
        }

        /**
         * Explicitly overrides the automatic retry-safety classification.
         *
         * <p>Use {@code true} for a non-idempotent request only when the application has an
         * end-to-end idempotency guarantee, such as a verified idempotency key contract.</p>
         */
        public Builder retrySafe(boolean retrySafe) {
            this.retrySafe = retrySafe;
            return this;
        }

        /** Builds an immutable, repeatable request snapshot. */
        public ClientRequest build() {
            return new ClientRequest(this);
        }
    }
}
