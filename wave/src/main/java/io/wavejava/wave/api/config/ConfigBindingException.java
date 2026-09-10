package io.wavejava.wave.api.config;

import java.util.Objects;
import java.util.Optional;

/**
 * Reports a typed configuration binding failure with its path, expected type, raw value, and
 * winning source when one exists.
 */
public final class ConfigBindingException extends IllegalArgumentException {
    private final String path;
    private final Class<?> expectedType;
    private final String rawValue;
    private final ConfigProvenance provenance;

    ConfigBindingException(
            String message,
            String path,
            Class<?> expectedType,
            String rawValue,
            ConfigProvenance provenance,
            Throwable cause
    ) {
        super(message, cause);
        this.path = ConfigPath.requirePrefix(path);
        this.expectedType = Objects.requireNonNull(expectedType, "expectedType");
        this.rawValue = rawValue;
        this.provenance = provenance;
    }

    /** The scalar or record path that could not be bound. The empty string denotes the root. */
    public String path() {
        return path;
    }

    /** The requested scalar or record type. */
    public Class<?> expectedType() {
        return expectedType;
    }

    /** The raw value that failed conversion, if a source supplied one. */
    public Optional<String> rawValue() {
        return Optional.ofNullable(rawValue);
    }

    /** The source that supplied the failing value, if one exists. */
    public Optional<ConfigProvenance> provenance() {
        return Optional.ofNullable(provenance);
    }

    static ConfigBindingException missing(String path, Class<?> expectedType) {
        return new ConfigBindingException(
                "Missing configuration path '" + displayPath(path) + "' required as " + expectedType.getTypeName(),
                path,
                expectedType,
                null,
                null,
                null);
    }

    static ConfigBindingException invalid(
            String path,
            Class<?> expectedType,
            String rawValue,
            ConfigProvenance provenance,
            String reason,
            Throwable cause
    ) {
        var sourceDescription = provenance == null ? "" : " from source '" + provenance.sourceName() + "'";
        var valueDescription = rawValue == null ? "" : " with value '" + rawValue + "'";
        return new ConfigBindingException(
                "Could not bind configuration path '" + displayPath(path) + "'" + sourceDescription
                        + " as " + expectedType.getTypeName() + valueDescription + ": " + reason,
                path,
                expectedType,
                rawValue,
                provenance,
                cause);
    }

    private static String displayPath(String path) {
        return path == null || path.isEmpty() ? "<root>" : path;
    }
}
