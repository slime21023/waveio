package io.waveio.http.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata associated with a registered route.
 *
 * <p>Carries an optional logical route name and custom key-value attributes.
 * This metadata is accessible during routing resolution and is forwarded to
 * {@link io.waveio.http.server.RequestObservation} for metrics, tracing, and access logs.
 *
 * @see #builder()
 * @see #empty()
 */
public final class RouteMetadata {
    private static final RouteMetadata EMPTY = new RouteMetadata(null, Map.of());

    private final String name;
    private final Map<String, String> attributes;

    private RouteMetadata(String name, Map<String, String> attributes) {
        this.name = name;
        this.attributes = Map.copyOf(attributes);
    }

    /**
     * Returns an empty route metadata instance.
     *
     * @return empty metadata
     */
    public static RouteMetadata empty() { return EMPTY; }

    /**
     * Creates a new mutable builder for constructing {@code RouteMetadata}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Returns the logical name of the route, if set.
     *
     * @return optional containing route name, or empty
     */
    public Optional<String> name() { return Optional.ofNullable(name); }

    /**
     * Returns an unmodifiable map of custom route attributes.
     *
     * @return map of metadata attributes
     */
    public Map<String, String> attributes() { return attributes; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RouteMetadata metadata
                && Objects.equals(name, metadata.name)
                && attributes.equals(metadata.attributes);
    }

    @Override public int hashCode() { return Objects.hash(name, attributes); }
    @Override public String toString() { return "RouteMetadata[name=" + name + ", attributes=" + attributes + "]"; }

    /**
     * Fluent builder for constructing immutable {@link RouteMetadata} instances.
     */
    public static final class Builder {
        private String name;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        /**
         * Sets the logical name for the route (e.g. {@code "users.show"}).
         *
         * @param value non-blank route name
         * @return this builder
         * @throws IllegalArgumentException if {@code value} is blank or null
         */
        public Builder name(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Route metadata name must not be blank");
            }
            name = value;
            return this;
        }

        /**
         * Adds a custom string key-value attribute.
         *
         * @param key non-blank attribute key
         * @param value non-null attribute value
         * @return this builder
         * @throws IllegalArgumentException if {@code key} is blank or {@code value} is null
         */
        public Builder attribute(String key, String value) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Route metadata attribute key must not be blank");
            }
            attributes.put(key, Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Adds all entries from the specified map as custom attributes.
         *
         * @param values map of attribute key-value pairs
         * @return this builder
         */
        public Builder attributes(Map<String, String> values) {
            Objects.requireNonNull(values, "values").forEach(this::attribute);
            return this;
        }

        /**
         * Builds an immutable {@link RouteMetadata} instance.
         *
         * @return a new {@code RouteMetadata}
         */
        public RouteMetadata build() {
            return name == null && attributes.isEmpty()
                    ? EMPTY : new RouteMetadata(name, attributes);
        }
    }
}
