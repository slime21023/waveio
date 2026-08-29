package io.waveio.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable collection of HTTP header fields with case-insensitive name lookups.
 *
 * <p>All header names are normalized using {@link Locale#ROOT} lowercase representation.
 * Supports multiple values associated with a single header name.
 *
 * @see #builder()
 * @see #first(String)
 * @see #all(String)
 */
public final class HttpHeaders {
    private final Map<String, List<String>> values;

    private HttpHeaders(Map<String, List<String>> values) {
        var copy = new LinkedHashMap<String, List<String>>();
        values.forEach((name, entries) -> copy.put(normalize(name), List.copyOf(entries)));
        this.values = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns an empty, immutable {@code HttpHeaders} instance.
     *
     * @return empty HTTP headers
     */
    public static HttpHeaders empty() {
        return new HttpHeaders(Map.of());
    }

    /**
     * Creates a new mutable builder for constructing {@code HttpHeaders}.
     *
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the first header value associated with the given name (case-insensitive).
     *
     * @param name header field name
     * @return an {@link Optional} containing the first value, or empty if not present
     */
    public Optional<String> first(String name) {
        var entries = values.get(normalize(name));
        return entries == null || entries.isEmpty() ? Optional.empty() : Optional.of(entries.getFirst());
    }

    /**
     * Returns all header values associated with the given name (case-insensitive).
     *
     * @param name header field name
     * @return an unmodifiable list of values, or an empty list if not present
     */
    public List<String> all(String name) {
        return values.getOrDefault(normalize(name), List.of());
    }

    /**
     * Returns an unmodifiable map view of all normalized header names and their associated values.
     *
     * @return unmodifiable map of headers
     */
    public Map<String, List<String>> asMap() {
        return values;
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Header name must not be blank");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Fluent builder for constructing immutable {@link HttpHeaders} instances.
     */
    public static final class Builder {
        private final Map<String, List<String>> values = new LinkedHashMap<>();

        /**
         * Appends a header value to the list of values for the given name.
         *
         * @param name header field name
         * @param value non-null header value
         * @return this builder
         * @throws IllegalArgumentException if {@code name} is blank or {@code value} is null
         */
        public Builder add(String name, String value) {
            if (value == null) {
                throw new IllegalArgumentException("Header value must not be null");
            }
            values.computeIfAbsent(normalize(name), ignored -> new ArrayList<>()).add(value);
            return this;
        }

        /**
         * Sets or replaces all values for the given header name with a single value.
         *
         * @param name header field name
         * @param value non-null header value
         * @return this builder
         * @throws IllegalArgumentException if {@code name} is blank or {@code value} is null
         */
        public Builder set(String name, String value) {
            values.put(normalize(name), new ArrayList<>(List.of(value)));
            return this;
        }

        /**
         * Builds an immutable {@link HttpHeaders} instance.
         *
         * @return a new {@code HttpHeaders}
         */
        public HttpHeaders build() {
            return new HttpHeaders(values);
        }
    }
}
