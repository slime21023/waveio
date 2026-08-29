package io.waveio.http.internal.netty;

import io.waveio.http.server.RequestObserver;
import java.time.Duration;
import java.util.List;

/**
 * Validated per-connection policy. Every value is a bound on one resource, so they are carried
 * together rather than as positional constructor arguments.
 */
record ConnectionOptions(int maxPendingRequests, Duration handlerTimeout,
        Duration responseStallTimeout, List<RequestObserver> observers,
        io.waveio.http.body.BodyCodec bodyCodec) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    ConnectionOptions {
        if (maxPendingRequests <= 0) {
            throw new IllegalArgumentException("maxPendingRequests must be positive");
        }
        requirePositive(handlerTimeout, "handlerTimeout");
        requirePositive(responseStallTimeout, "responseStallTimeout");
        observers = List.copyOf(observers);
    }

    static ConnectionOptions defaults() {
        return new ConnectionOptions(16, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT, List.of(), null);
    }

    ConnectionOptions withMaxPendingRequests(int value) {
        return new ConnectionOptions(value, handlerTimeout, responseStallTimeout, observers,
                bodyCodec);
    }

    ConnectionOptions withHandlerTimeout(Duration value) {
        return new ConnectionOptions(maxPendingRequests, value, responseStallTimeout, observers,
                bodyCodec);
    }

    ConnectionOptions withResponseStallTimeout(Duration value) {
        return new ConnectionOptions(maxPendingRequests, handlerTimeout, value, observers,
                bodyCodec);
    }

    ConnectionOptions withObservers(List<RequestObserver> value) {
        return new ConnectionOptions(maxPendingRequests, handlerTimeout, responseStallTimeout,
                value, bodyCodec);
    }

    ConnectionOptions withBodyCodec(io.waveio.http.body.BodyCodec value) {
        return new ConnectionOptions(maxPendingRequests, handlerTimeout, responseStallTimeout,
                observers, value);
    }

    /** Nanosecond form of a duration, saturating instead of overflowing. */
    static long nanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
