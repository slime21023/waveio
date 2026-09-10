package io.wavejava.wave.api.server;

/**
 * Selects the HTTP server representation for request bodies.
 *
 * <p>{@link #AGGREGATED} is the compatibility default: HTTP/1.1 accepts a complete, bounded
 * {@code Body} before application invocation begins. {@link #STREAMING} invokes the application
 * after request headers and exposes a single-consumption Flow body instead. The two forms are
 * deliberately mutually exclusive on {@code Request}; an application must select the mode that
 * matches the routes it serves.</p>
 */
public enum RequestBodyMode {
    /** Buffer the complete body within {@link ServerLimits#maximumRequestBodyBytes()}. */
    AGGREGATED,

    /** Deliver bounded HTTP content chunks through {@code Request.streamingBody()}. */
    STREAMING
}
