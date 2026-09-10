package io.wavejava.wave.api.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable, case-insensitive HTTP header multimap.
 *
 * <p>Header names retain their first supplied spelling for rendering, but lookup and equality use
 * lower-case ASCII names. Values are ordered and never contain CR/LF, preventing response-splitting
 * when values are later written by a transport adapter.</p>
 */
public final class Headers {
    private static final Headers EMPTY = new Headers(Map.of());

    private final Map<String, Entry> entries;

    private Headers(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** Returns an empty header set. */
    public static Headers empty() {
        return EMPTY;
    }

    /** Returns a header set containing one value. */
    public static Headers of(String name, String value) {
        return builder().add(name, value).build();
    }

    /** Creates a mutable builder for an immutable header set. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the first value for {@code name}, if present. */
    public Optional<String> first(String name) {
        var entry = entries.get(normalizeName(name));
        return entry == null ? Optional.empty() : Optional.of(entry.values.getFirst());
    }

    /** Returns all values for {@code name} in wire order. */
    public List<String> all(String name) {
        var entry = entries.get(normalizeName(name));
        return entry == null ? List.of() : entry.values;
    }

    /** Returns whether at least one value exists for {@code name}. */
    public boolean contains(String name) {
        return entries.containsKey(normalizeName(name));
    }

    /** Returns header names in first-insertion order. */
    public Set<String> names() {
        var names = new LinkedHashSet<String>();
        entries.values().forEach(entry -> names.add(entry.displayName));
        return Collections.unmodifiableSet(names);
    }

    /** Returns an immutable map of display names to ordered values. */
    public Map<String, List<String>> asMap() {
        var result = new LinkedHashMap<String, List<String>>();
        entries.values().forEach(entry -> result.put(entry.displayName, entry.values));
        return Collections.unmodifiableMap(result);
    }

    /** Returns a builder pre-populated with this header set. */
    public Builder toBuilder() {
        var builder = new Builder();
        entries.forEach((name, entry) -> builder.entries.put(name, new MutableEntry(entry.displayName, new ArrayList<>(entry.values))));
        return builder;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Headers headers) || entries.size() != headers.entries.size()) {
            return false;
        }
        for (var entry : entries.entrySet()) {
            var otherEntry = headers.entries.get(entry.getKey());
            if (otherEntry == null || !entry.getValue().values.equals(otherEntry.values)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        var hash = 0;
        for (var entry : entries.entrySet()) {
            hash += 31 * entry.getKey().hashCode() + entry.getValue().values.hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        return asMap().toString();
    }

    static String normalizeName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Header name must not be empty");
        }
        for (var index = 0; index < name.length(); index++) {
            if (!HttpMethod.isTokenCharacter(name.charAt(index))) {
                throw new IllegalArgumentException("Invalid HTTP header name: " + name);
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    static String validateValue(String value) {
        Objects.requireNonNull(value, "value");
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == 0 || character == 0x7f
                    || (character < 0x20 && character != '\t')) {
                throw new IllegalArgumentException("HTTP header value contains an unsafe control character");
            }
        }
        return value;
    }

    /** Builder for {@link Headers}. A builder is not thread-safe. */
    public static final class Builder {
        private final LinkedHashMap<String, MutableEntry> entries = new LinkedHashMap<>();

        /** Appends a value under {@code name}. */
        public Builder add(String name, String value) {
            var normalizedName = normalizeName(name);
            var validatedValue = validateValue(value);
            entries.compute(normalizedName, (ignored, current) -> {
                if (current == null) {
                    return new MutableEntry(name, new ArrayList<>(List.of(validatedValue)));
                }
                current.values.add(validatedValue);
                return current;
            });
            return this;
        }

        /** Replaces all values for {@code name} with one value. */
        public Builder set(String name, String value) {
            var normalizedName = normalizeName(name);
            entries.put(normalizedName, new MutableEntry(name, new ArrayList<>(List.of(validateValue(value)))));
            return this;
        }

        /** Removes every value for {@code name}. */
        public Builder remove(String name) {
            entries.remove(normalizeName(name));
            return this;
        }

        /** Adds every value from {@code headers}. */
        public Builder addAll(Headers headers) {
            Objects.requireNonNull(headers, "headers");
            headers.entries.values().forEach(entry -> entry.values.forEach(value -> add(entry.displayName, value)));
            return this;
        }

        /** Creates an immutable header set. */
        public Headers build() {
            if (entries.isEmpty()) {
                return EMPTY;
            }
            var result = new LinkedHashMap<String, Entry>();
            entries.forEach((name, entry) -> result.put(name, new Entry(entry.displayName, List.copyOf(entry.values))));
            return new Headers(result);
        }
    }

    private record Entry(String displayName, List<String> values) {
        private Entry {
            Objects.requireNonNull(displayName, "displayName");
            values = List.copyOf(values);
        }
    }

    private static final class MutableEntry {
        private final String displayName;
        private final List<String> values;

        private MutableEntry(String displayName, List<String> values) {
            this.displayName = displayName;
            this.values = values;
        }
    }
}
