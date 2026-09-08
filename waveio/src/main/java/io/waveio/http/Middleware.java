package io.waveio.http;

import io.waveio.task.Task;

/** Wraps high-level endpoint execution without accessing response transactions. */
@FunctionalInterface
public interface Middleware {
    /** Produces a response directly or delegates to the next endpoint. */
    Task<HttpResponse> handle(EndpointContext context, Next next);
}
