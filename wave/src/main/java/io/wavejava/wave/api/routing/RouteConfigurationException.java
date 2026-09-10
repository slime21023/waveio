package io.wavejava.wave.api.routing;

/** Thrown when registered routes cannot be compiled into one unambiguous route table. */
public final class RouteConfigurationException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    /** Creates a configuration failure with an actionable description. */
    public RouteConfigurationException(String message) {
        super(message);
    }
}
