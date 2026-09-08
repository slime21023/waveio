package io.waveio.http;

import java.util.Objects;

/** Immutable HTTP request metadata. */
public record HttpRequest(HttpMethod method, RequestUri uri, Headers headers, Body body) {
    /** Validates request metadata. */ public HttpRequest { method = Objects.requireNonNull(method, "method"); uri = Objects.requireNonNull(uri, "uri"); headers = Objects.requireNonNull(headers, "headers"); body = Objects.requireNonNull(body, "body"); }
    /** Creates request metadata with an empty body. */ public HttpRequest(HttpMethod method, RequestUri uri, Headers headers) { this(method, uri, headers, Body.empty()); }
}
