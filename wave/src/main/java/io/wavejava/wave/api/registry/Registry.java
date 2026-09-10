package io.wavejava.wave.api.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Typed, immutable, optionally layered lookup for framework services.
 *
 * <p>Only exact registered keys are resolved. A child registry overrides its parent for the same
 * key, while duplicate keys within one builder are rejected because they make startup wiring
 * ambiguous.</p>
 */
public final class Registry {
    private static final Registry EMPTY = new Registry(Map.of(), null);

    private final Map<Class<?>, Object> local;
    private final Registry parent;

    private Registry(Map<Class<?>, Object> local, Registry parent) {
        this.local = Collections.unmodifiableMap(new LinkedHashMap<>(local));
        this.parent = parent;
    }

    /** Returns the immutable empty registry. */
    public static Registry empty() {
        return EMPTY;
    }

    /** Starts a builder with no parent registry. */
    public static Builder builder() {
        return new Builder();
    }

    /** Starts a builder layered over {@code parent}. */
    public static Builder builder(Registry parent) {
        return builder().parent(parent);
    }

    /** Returns the matching local service or inherited service, if present. */
    public <T> Optional<T> find(Class<T> type) {
        Objects.requireNonNull(type, "type");
        var value = local.get(type);
        if (value != null) {
            return Optional.of(type.cast(value));
        }
        return parent == null ? Optional.empty() : parent.find(type);
    }

    /** Returns a matching service or throws with its missing key. */
    public <T> T require(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return find(type).orElseThrow(() -> new NoSuchElementException("No registry service for type " + type.getTypeName()));
    }

    /** Returns whether this registry or one of its parents contains {@code type}. */
    public boolean contains(Class<?> type) {
        return find(Objects.requireNonNull(type, "type")).isPresent();
    }

    /** Returns the immutable parent registry, if this is a layered registry. */
    public Optional<Registry> parent() {
        return Optional.ofNullable(parent);
    }

    /** Returns only types registered directly in this registry, in insertion order. */
    public Set<Class<?>> localTypes() {
        return local.keySet();
    }

    /** Builder for an immutable registry. */
    public static final class Builder {
        private final Map<Class<?>, Object> local = new LinkedHashMap<>();
        private Registry parent;

        private Builder() {
        }

        /** Sets the immutable parent used as a fallback for this registry. */
        public Builder parent(Registry parent) {
            this.parent = Objects.requireNonNull(parent, "parent");
            return this;
        }

        /**
         * Registers one framework service under an explicit API type.
         *
         * @throws IllegalArgumentException when {@code service} does not implement {@code type}
         * @throws IllegalStateException when this builder already has the same key
         */
        public <T> Builder add(Class<T> type, T service) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(service, "service");
            if (!type.isInstance(service)) {
                throw new IllegalArgumentException(
                        "Registry service " + service.getClass().getTypeName() + " is not assignable to " + type.getTypeName());
            }
            if (local.containsKey(type)) {
                throw new IllegalStateException("A registry service is already registered for type " + type.getTypeName());
            }
            local.put(type, service);
            return this;
        }

        /** Produces an immutable registry snapshot. */
        public Registry build() {
            if (local.isEmpty() && parent == null) {
                return EMPTY;
            }
            return new Registry(local, parent);
        }
    }
}
