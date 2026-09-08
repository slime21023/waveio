package io.waveio.http;

import java.util.Objects;

/** Immutable HTTP response metadata. */
public record HttpResponse(HttpStatus status, Headers headers, Body body) {
    /** Validates response metadata. */ public HttpResponse { status = Objects.requireNonNull(status, "status"); headers = Objects.requireNonNull(headers, "headers"); body = Objects.requireNonNull(body, "body"); }
    /** Creates a response with an empty body. */ public HttpResponse(HttpStatus status, Headers headers) { this(status, headers, Body.empty()); }
    /** Creates an empty response with the supplied status. */ public static HttpResponse of(HttpStatus status) { return new HttpResponse(status, Headers.empty()); }
}
