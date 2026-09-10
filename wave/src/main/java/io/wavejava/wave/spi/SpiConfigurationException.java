package io.wavejava.wave.spi;

/** Thrown when a ServiceLoader extension set is malformed, over budget, or ambiguous. */
public final class SpiConfigurationException extends IllegalStateException {
    /** Creates a configuration failure with a diagnostic message. */
    public SpiConfigurationException(String message) {
        super(message);
    }

    /** Creates a configuration failure that retains its loading cause. */
    public SpiConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
