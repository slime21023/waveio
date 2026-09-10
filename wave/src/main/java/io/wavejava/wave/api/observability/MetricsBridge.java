package io.wavejava.wave.api.observability;

/**
 * Optional metrics integration invoked for a completed HTTP access event.
 *
 * <p>Wave invokes this bridge only on its bounded observability worker, never on a Netty
 * EventLoop or a request handler thread. Implementations should use the event's route pattern and
 * outcome enums as dimensions rather than raw request data.</p>
 */
@FunctionalInterface
public interface MetricsBridge {
    /** Records one completed request without retaining unbounded request data. */
    void record(AccessLogEvent event);
}
