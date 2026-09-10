package io.wavejava.wave.api.observability;

import io.wavejava.wave.api.middleware.Outcome;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, bounded completion record for one HTTP request.
 *
 * <p>The record deliberately contains route patterns rather than raw request targets, and never
 * contains headers, cookies, query values, exception messages, or bodies. This keeps the default
 * dimensions safe for metrics and prevents access logging from accidentally retaining secrets or
 * unbounded cardinality. A {@linkplain #status() status} of {@code 0} means no HTTP response was
 * available before the connection was cancelled or failed. {@linkplain #responseBodyBytes()
 * Response bytes} are {@code -1} when a streaming or upgraded response has no finite final body
 * count at HTTP completion.</p>
 */
public final class AccessLogEvent {
    /** The result of attempting to deliver the HTTP response to the peer. */
    public enum TransportOutcome {
        /** The complete HTTP response write was acknowledged by the transport. */
        WRITTEN,
        /** The peer, deadline, or server shutdown cancelled the response before completion. */
        CANCELLED,
        /** A transport, framing, or ordered-write failure prevented completion. */
        FAILED
    }

    private static final int MAXIMUM_REQUEST_ID_BYTES = 128;
    private static final int MAXIMUM_METHOD_BYTES = 32;
    private static final int MAXIMUM_ROUTE_BYTES = 256;

    private final String requestId;
    private final String method;
    private final String route;
    private final int status;
    private final Instant startedAt;
    private final Duration latency;
    private final long responseBodyBytes;
    private final Outcome.Kind applicationOutcome;
    private final TransportOutcome transportOutcome;

    /**
     * Creates a safe completion event.
     *
     * @param status an HTTP status in the range 100--599, or {@code 0} when no response existed
     * @param responseBodyBytes non-negative bytes, or {@code -1} when the final count is unknown
     */
    public AccessLogEvent(
            String requestId,
            String method,
            String route,
            int status,
            Instant startedAt,
            Duration latency,
            long responseBodyBytes,
            Outcome.Kind applicationOutcome,
            TransportOutcome transportOutcome) {
        this.requestId = safeToken(requestId, "requestId", MAXIMUM_REQUEST_ID_BYTES);
        this.method = safeToken(method, "method", MAXIMUM_METHOD_BYTES);
        this.route = safeToken(route, "route", MAXIMUM_ROUTE_BYTES);
        if (status != 0 && (status < 100 || status > 599)) {
            throw new IllegalArgumentException("status must be 0 or an HTTP status from 100 to 599: " + status);
        }
        this.status = status;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.latency = requireNonNegative(latency, "latency");
        if (responseBodyBytes < -1) {
            throw new IllegalArgumentException("responseBodyBytes must be non-negative or -1 when unknown");
        }
        this.responseBodyBytes = responseBodyBytes;
        this.applicationOutcome = Objects.requireNonNull(applicationOutcome, "applicationOutcome");
        this.transportOutcome = Objects.requireNonNull(transportOutcome, "transportOutcome");
    }

    /** Returns the transport-created stable request identifier. */
    public String requestId() {
        return requestId;
    }

    /** Returns the normalized HTTP method. */
    public String method() {
        return method;
    }

    /** Returns a matched route pattern or one of Wave's low-cardinality unmatched labels. */
    public String route() {
        return route;
    }

    /** Returns the HTTP response status, or {@code 0} when a response was never available. */
    public int status() {
        return status;
    }

    /** Returns the wall-clock instant at which Wave admitted the application invocation. */
    public Instant startedAt() {
        return startedAt;
    }

    /** Returns monotonic elapsed time from application admission through transport completion. */
    public Duration latency() {
        return latency;
    }

    /**
     * Returns response body bytes acknowledged by Wave's HTTP transport, or {@code -1} when the
     * final count is unknown for an upgraded connection.
     */
    public long responseBodyBytes() {
        return responseBodyBytes;
    }

    /** Returns the terminal result of Wave's application invocation. */
    public Outcome.Kind applicationOutcome() {
        return applicationOutcome;
    }

    /** Returns whether the transport wrote, cancelled, or failed the HTTP response. */
    public TransportOutcome transportOutcome() {
        return transportOutcome;
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        var value = Objects.requireNonNull(duration, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static String safeToken(String value, String name, int maximumBytes) {
        var candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank() || containsControl(candidate)
                || candidate.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(name + " must be safe, non-blank, and at most "
                    + maximumBytes + " UTF-8 bytes");
        }
        return candidate;
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character == '\r' || character == '\n' || character == '\0');
    }
}
