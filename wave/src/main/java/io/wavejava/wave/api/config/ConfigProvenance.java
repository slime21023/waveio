package io.wavejava.wave.api.config;

/**
 * Identifies the source and merge position that supplied a configuration value.
 *
 * <p>A greater {@link #precedence()} means the source was added later and therefore won over an
 * earlier source for the same path.</p>
 */
public record ConfigProvenance(String sourceName, int precedence) {
    public ConfigProvenance {
        sourceName = ConfigSource.requireName(sourceName);
        if (precedence < 0) {
            throw new IllegalArgumentException("Configuration source precedence must not be negative: " + precedence);
        }
    }
}
