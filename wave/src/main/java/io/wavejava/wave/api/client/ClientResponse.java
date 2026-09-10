package io.wavejava.wave.api.client;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Immutable, bounded byte response returned by {@link WaveClient}. */
public final class ClientResponse {
    private final int status;
    private final Headers headers;
    private final byte[] body;
    private final URI uri;
    private final int redirectsFollowed;

    public ClientResponse(int status, Headers headers, byte[] body, URI uri, int redirectsFollowed) {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("Invalid HTTP response status: " + status);
        }
        if (redirectsFollowed < 0) {
            throw new IllegalArgumentException("redirectsFollowed must not be negative: " + redirectsFollowed);
        }
        this.status = status;
        this.headers = Objects.requireNonNull(headers, "headers");
        this.body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
        this.uri = Objects.requireNonNull(uri, "uri");
        this.redirectsFollowed = redirectsFollowed;
    }

    /** Returns the HTTP response status. */
    public int status() {
        return status;
    }

    /** Returns immutable response headers. */
    public Headers headers() {
        return headers;
    }

    /** Returns the first response header value, if present. */
    public Optional<String> header(String name) {
        return headers.first(name);
    }

    /** Returns a defensive copy of the bounded response body. */
    public byte[] body() {
        return body.clone();
    }

    /** Returns the bounded response-body length. */
    public int bodyLength() {
        return body.length;
    }

    /** Decodes the body using Content-Type charset or UTF-8 when the header is absent or invalid. */
    public String text() {
        return new String(body, responseCharset());
    }

    /** Returns the final target URI after any followed redirects. */
    public URI uri() {
        return uri;
    }

    /** Returns the number of redirects followed before this response. */
    public int redirectsFollowed() {
        return redirectsFollowed;
    }

    private Charset responseCharset() {
        var contentType = headers.first("Content-Type");
        if (contentType.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return MediaType.parse(contentType.get()).charset().orElse(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return StandardCharsets.UTF_8;
        }
    }
}
