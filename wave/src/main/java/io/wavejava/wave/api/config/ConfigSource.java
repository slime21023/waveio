package io.wavejava.wave.api.config;

import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * One named source of flat, dotted configuration values.
 *
 * <p>The source interface enables file, environment, and programmatic adapters without giving
 * them write access to a built {@link Config}. Callers should treat {@link #values()} as a
 * snapshot; {@link Config.Builder} defensively snapshots it again when assembling configuration.
 * When sources are added to a config, values from later sources take precedence over earlier
 * values.</p>
 */
public interface ConfigSource {
    /** A stable, human-readable source name used in diagnostics and provenance. */
    String name();

    /** Flat dotted configuration values supplied by this source. Values must not be {@code null}. */
    Map<String, String> values();

    /** Creates an immutable source snapshot from programmatic values. */
    static ConfigSource of(String name, Map<String, ?> values) {
        return new SnapshotConfigSource(name, snapshotValues(values));
    }

    /** Creates an immutable source snapshot from a properties object. */
    static ConfigSource properties(String name, Properties properties) {
        Objects.requireNonNull(properties, "properties");
        var values = new LinkedHashMap<String, Object>();
        for (var key : properties.stringPropertyNames()) {
            values.put(key, properties.getProperty(key));
        }
        return of(name, values);
    }

    /** Captures the current process environment under the conventional {@code environment} name. */
    static ConfigSource environment() {
        return of("environment", System.getenv());
    }

    /** Captures the current JVM system properties under the conventional {@code system-properties} name. */
    static ConfigSource systemProperties() {
        return properties("system-properties", System.getProperties());
    }

    /** Captures a supplied environment-like mapping under a caller-defined source name. */
    static ConfigSource environment(String name, Map<String, String> environment) {
        return of(name, environment);
    }

    static String requireName(String name) {
        Objects.requireNonNull(name, "source name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Configuration source name must not be blank");
        }
        return name;
    }

    static Map<String, String> snapshotValues(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        var ordered = new LinkedHashMap<String, String>();
        values.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> {
                    var path = ConfigPath.requirePath(entry.getKey());
                    var value = Objects.requireNonNull(entry.getValue(), "Configuration value for '" + path + "'");
                    ordered.put(path, value instanceof String string ? string : String.valueOf(value));
                });
        return Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }
}

/** Immutable implementation shared by built-in source factories and Config snapshots. */
final class SnapshotConfigSource implements ConfigSource {
    private final String name;
    private final Map<String, String> values;

    SnapshotConfigSource(String name, Map<String, String> values) {
        this.name = ConfigSource.requireName(name);
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<String, String> values() {
        return values;
    }
}
