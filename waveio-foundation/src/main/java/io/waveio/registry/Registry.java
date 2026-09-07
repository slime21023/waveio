package io.waveio.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** An immutable registry of eager, non-null service instances. */
public final class Registry {
    private final Map<Key<?>, Object> entries;

    private Registry(Map<Key<?>, Object> entries) {
        this.entries = Map.copyOf(entries);
    }

    /** Returns an empty registry. */
    public static Registry empty() {
        return new Registry(Map.of());
    }

    /** Returns a builder for an immutable registry snapshot. */
    public static Builder builder() {
        return new Builder();
    }

    /** Looks up a service or throws if no matching entry exists. */
    public <T> T get(Key<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = entries.get(key);
        if (value == null) {
            throw new MissingRegistryEntryException(key);
        }
        return key.type().cast(value);
    }

    /** Returns a snapshot in which {@code overlay} takes precedence over this registry. */
    public Registry overlay(Registry overlay) {
        Objects.requireNonNull(overlay, "overlay");
        Map<Key<?>, Object> combined = new LinkedHashMap<>(entries);
        combined.putAll(overlay.entries);
        return new Registry(combined);
    }

    /** Builder for a single immutable registry snapshot. */
    public static final class Builder {
        private final Map<Key<?>, Object> entries = new LinkedHashMap<>();
        private boolean built;

        private Builder() {
        }

        /** Binds one eager service instance, rejecting duplicate keys and raw-type mismatches. */
        public <T> Builder bind(Key<T> key, T instance) {
            ensureMutable();
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(instance, "instance");
            if (!key.type().isInstance(instance)) {
                throw new IllegalArgumentException("instance does not match " + key);
            }
            if (entries.putIfAbsent(key, instance) != null) {
                throw new IllegalArgumentException("duplicate registry binding for " + key);
            }
            return this;
        }

        /** Builds the immutable snapshot and permanently closes this builder. */
        public Registry build() {
            ensureMutable();
            built = true;
            return new Registry(entries);
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("registry builder has already built a snapshot");
            }
        }
    }
}
