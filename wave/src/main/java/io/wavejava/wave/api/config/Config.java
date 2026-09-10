package io.wavejava.wave.api.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, flat configuration snapshot with per-value provenance.
 *
 * <p>Sources are merged in insertion order; a later source replaces an earlier value with the
 * same path. The resulting entries are stored in lexical path order, so both inspection and
 * binding are reproducible even when an input mapping has no iteration order.</p>
 */
public final class Config {
    private static final Config EMPTY = new Config(Map.of(), List.of());

    private final Map<String, ConfigEntry> entries;
    private final List<ConfigSource> sources;

    private Config(Map<String, ConfigEntry> entries, List<ConfigSource> sources) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.sources = List.copyOf(sources);
    }

    /** Returns the immutable empty configuration snapshot. */
    public static Config empty() {
        return EMPTY;
    }

    /** Starts a builder for deterministic source merging. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a configuration from sources in the supplied order, with later sources winning. */
    public static Config of(ConfigSource... sources) {
        Objects.requireNonNull(sources, "sources");
        var builder = builder();
        for (var source : sources) {
            builder.add(source);
        }
        return builder.build();
    }

    /** Returns the complete immutable entry, including provenance, for {@code path}. */
    public Optional<ConfigEntry> entry(String path) {
        return Optional.ofNullable(entries.get(ConfigPath.requirePath(path)));
    }

    /** Returns a configuration value, if present. */
    public Optional<String> find(String path) {
        return entry(path).map(ConfigEntry::value);
    }

    /** Alias for {@link #find(String)}. */
    public Optional<String> value(String path) {
        return find(path);
    }

    /** Returns a configuration value or throws with the missing path. */
    public String require(String path) {
        return find(path).orElseThrow(() -> new NoSuchElementException("No configuration value for path '" + path + "'"));
    }

    /** Returns a configuration value or {@code defaultValue} when no value exists. */
    public String valueOrDefault(String path, String defaultValue) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        return find(path).orElse(defaultValue);
    }

    /** Returns the winning source and precedence for {@code path}, if the path exists. */
    public Optional<ConfigProvenance> provenance(String path) {
        return entry(path).map(ConfigEntry::provenance);
    }

    /** Returns all values in deterministic lexical path order. */
    public Map<String, String> values() {
        var values = new LinkedHashMap<String, String>();
        entries.forEach((path, entry) -> values.put(path, entry.value()));
        return Collections.unmodifiableMap(values);
    }

    /** Returns all entries, including provenance, in deterministic lexical path order. */
    public Map<String, ConfigEntry> entries() {
        return entries;
    }

    /** Returns immutable snapshots of the sources used to build this configuration. */
    public List<ConfigSource> sources() {
        return sources;
    }

    /** Binds a top-level scalar or record using the standard binding rules. */
    public <T> T bind(Class<T> type) {
        return ConfigBinder.bind(this, type);
    }

    /** Binds a scalar or record under a dotted {@code path} using the standard binding rules. */
    public <T> T bind(String path, Class<T> type) {
        return ConfigBinder.bind(this, path, type);
    }

    Optional<ConfigProvenance> firstProvenanceAtOrBelow(String prefix) {
        var normalized = ConfigPath.requirePrefix(prefix);
        if (normalized.isEmpty()) {
            return entries.values().stream().findFirst().map(ConfigEntry::provenance);
        }
        var prefixWithSeparator = normalized + '.';
        return entries.entrySet().stream()
                .filter(entry -> entry.getKey().equals(normalized) || entry.getKey().startsWith(prefixWithSeparator))
                .map(Map.Entry::getValue)
                .map(ConfigEntry::provenance)
                .findFirst();
    }

    /** Builder for one immutable configuration snapshot. */
    public static final class Builder {
        private final List<ConfigSource> sources = new ArrayList<>();

        private Builder() {
        }

        /** Adds a source. Values from sources added later have greater precedence. */
        public Builder add(ConfigSource source) {
            sources.add(Objects.requireNonNull(source, "source"));
            return this;
        }

        /** Alias for {@link #add(ConfigSource)} that reads naturally in application assembly. */
        public Builder source(ConfigSource source) {
            return add(source);
        }

        /** Adds every source in iteration order. */
        public Builder addAll(Iterable<? extends ConfigSource> sources) {
            Objects.requireNonNull(sources, "sources");
            for (var source : sources) {
                add(source);
            }
            return this;
        }

        /** Produces an immutable snapshot and captures each source's current values. */
        public Config build() {
            if (sources.isEmpty()) {
                return EMPTY;
            }
            var merged = new LinkedHashMap<String, ConfigEntry>();
            var snapshots = new ArrayList<ConfigSource>(sources.size());
            for (var precedence = 0; precedence < sources.size(); precedence++) {
                var source = sources.get(precedence);
                var sourceName = ConfigSource.requireName(source.name());
                var snapshotValues = ConfigSource.snapshotValues(source.values());
                snapshots.add(new SnapshotConfigSource(sourceName, snapshotValues));
                for (var entry : snapshotValues.entrySet()) {
                    merged.put(entry.getKey(), new ConfigEntry(entry.getValue(), new ConfigProvenance(sourceName, precedence)));
                }
            }
            var ordered = new LinkedHashMap<String, ConfigEntry>();
            merged.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
            return new Config(ordered, snapshots);
        }
    }
}
