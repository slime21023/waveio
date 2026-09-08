package io.waveio.http;

import io.waveio.task.Task;

/** A high-level endpoint that asynchronously produces exactly one response. */
@FunctionalInterface
public interface Endpoint {
    /** Produces a response for an immutable request view. */
    Task<HttpResponse> handle(EndpointContext context);
}
