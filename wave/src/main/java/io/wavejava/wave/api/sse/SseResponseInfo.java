package io.wavejava.wave.api.sse;

import io.wavejava.wave.api.http.Headers;
import java.net.URI;
import java.util.Objects;

/** Immutable metadata for one successfully opened SSE HTTP response. */
public record SseResponseInfo(int status, Headers headers, URI uri) {
    public SseResponseInfo {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("Invalid SSE HTTP status: " + status);
        }
        headers = Objects.requireNonNull(headers, "headers");
        uri = Objects.requireNonNull(uri, "uri");
    }
}

