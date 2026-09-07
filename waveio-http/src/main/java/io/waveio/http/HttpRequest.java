package io.waveio.http;

import java.util.Objects;

/** Immutable HTTP request metadata. */
public record HttpRequest(HttpMethod method, RequestUri uri, Headers headers) {
    /** Validates request metadata. */ public HttpRequest { method = Objects.requireNonNull(method, "method"); uri = Objects.requireNonNull(uri, "uri"); headers = Objects.requireNonNull(headers, "headers"); }
}
