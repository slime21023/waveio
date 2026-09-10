package io.wavejava.wave.api.config;

import java.util.Objects;

/** One immutable configuration value together with the source that won during merging. */
public record ConfigEntry(String value, ConfigProvenance provenance) {
    public ConfigEntry {
        value = Objects.requireNonNull(value, "value");
        provenance = Objects.requireNonNull(provenance, "provenance");
    }
}
