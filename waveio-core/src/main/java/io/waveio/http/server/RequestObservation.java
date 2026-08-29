package io.waveio.http.server;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpStatus;
import io.waveio.http.routing.RouteMetadata;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot observation emitted upon the terminal completion of an HTTP request lifecycle.
 *
 * <p>Delivered to registered {@link RequestObserver} listeners. Contains matched route patterns,
 * metadata, final status code, failure details, and total processing duration.
 *
 * @param request the completed HTTP request
 * @param pathPattern matched route template pattern (e.g. {@code "/users/:id"}), or empty if unmatched
 * @param routeMetadata attached route metadata
 * @param outcome terminal outcome category
 * @param status final HTTP response status, or empty if disconnected before response headers were formed
 * @param failure uncaught exception if request failed, or empty
 * @param elapsed total elapsed duration from initial socket read to terminal completion
 *
 * @see RequestObserver
 * @see Outcome
 */
public record RequestObservation(HttpRequest request, Optional<String> pathPattern,
        RouteMetadata routeMetadata, Outcome outcome, Optional<HttpStatus> status,
        Optional<Throwable> failure, Duration elapsed) {
    public RequestObservation {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(pathPattern, "pathPattern");
        Objects.requireNonNull(routeMetadata, "routeMetadata");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) throw new IllegalArgumentException("elapsed must not be negative");
    }

    /**
     * Terminal outcome category of the request.
     */
    public enum Outcome {
        /** Request completed normally with an HTTP response. */
        SUCCESS,
        /** Request failed due to an unhandled application exception or 5xx server error. */
        FAILURE,
        /** Request timed out (e.g., handler timeout, write deadline). */
        TIMEOUT,
        /** Client disconnected prematurely before response transmission completed. */
        DISCONNECTED
    }
}
