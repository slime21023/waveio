package io.waveio.http;

import io.waveio.task.Task;
import java.util.Objects;

/** Maps an endpoint failure to a response before any response is committed. */
@FunctionalInterface
public interface ErrorHandler {
    /** Produces an error response for an endpoint failure. */
    Task<HttpResponse> handle(EndpointContext context, Throwable failure);

    /** Returns the minimal fallback error policy. */
    static ErrorHandler fallback() {
        return (context, failure) -> {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(failure, "failure");
            return Task.success(HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
        };
    }
}
