package io.wavejava.wave.spi;

import java.util.Objects;

/**
 * Common metadata for a controlled Wave extension discovered through {@link java.util.ServiceLoader}.
 *
 * <p>A provider identity is stable diagnostic data, not an implementation-class name inferred by
 * Wave. {@link #selectionKey()} identifies the capability slot in which priority is meaningful:
 * providers with the same selection key may coexist at different priorities, while an equal
 * priority is an ambiguous startup configuration and is rejected before a listener binds.</p>
 */
public interface WaveProvider {
    /** Returns a globally unique, stable provider identifier. */
    String id();

    /**
     * Returns this provider's precedence within its selection key. Higher values run or are
     * selected first. The default is intentionally neutral rather than relying on classpath order.
     */
    default int priority() {
        return 0;
    }

    /**
     * Returns the capability slot whose providers are ordered by {@link #priority()}.
     *
     * <p>The default makes every provider independent. A parser, renderer, or store family that
     * deliberately competes for the same capability must override this value with the same stable
     * key in each contender.</p>
     */
    default String selectionKey() {
        return id();
    }

    /** Validates one provider metadata value for provider implementations and contract kits. */
    static String requireStableName(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
