package io.wavejava.wave.api.observability;

/**
 * Optional tracing integration invoked after an HTTP request reaches a terminal transport state.
 *
 * <p>The bridge receives completion-only data so the core has no OpenTelemetry dependency. A
 * provider may create/export a span from this safe event or correlate it using {@link
 * AccessLogEvent#requestId()}.</p>
 */
@FunctionalInterface
public interface TracingBridge {
    /** Records or exports one completed request trace event. */
    void record(AccessLogEvent event);
}
