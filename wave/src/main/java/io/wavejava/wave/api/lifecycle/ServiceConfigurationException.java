package io.wavejava.wave.api.lifecycle;

/** Thrown before startup when a service graph has duplicate IDs, missing dependencies, or a cycle. */
public final class ServiceConfigurationException extends IllegalStateException {
    ServiceConfigurationException(String message) {
        super(message);
    }
}
