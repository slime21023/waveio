package io.waveio.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable case-insensitive HTTP headers retaining all supplied values. */
public final class Headers {
    private final Map<String, List<String>> values;
    private Headers(Map<String, List<String>> values) { this.values = values; }
    /** Returns empty headers. */ public static Headers empty() { return new Headers(Map.of()); }
    /** Returns a builder. */ public static Builder builder() { return new Builder(); }
    /** Returns all values for a field name. */ public List<String> all(String name) { return values.getOrDefault(normalize(name), List.of()); }
    /** Returns the first value, or null when absent. */ public String first(String name) { List<String> all = all(name); return all.isEmpty() ? null : all.getFirst(); }
    /** Builder for immutable headers. */ public static final class Builder {
        private final Map<String, List<String>> values = new LinkedHashMap<>();
        private Builder() { }
        /** Appends one non-empty header value. */ public Builder add(String name, String value) {
            String field = normalize(name); Objects.requireNonNull(value, "value");
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) { throw new IllegalArgumentException("header value must not contain line breaks"); }
            values.computeIfAbsent(field, unused -> new ArrayList<>()).add(value); return this;
        }
        /** Builds an immutable snapshot. */ public Headers build() {
            Map<String, List<String>> copy = new LinkedHashMap<>(); values.forEach((name, entries) -> copy.put(name, List.copyOf(entries)));
            return new Headers(Collections.unmodifiableMap(copy));
        }
    }
    private static String normalize(String name) {
        Objects.requireNonNull(name, "name"); if (name.isBlank() || name.chars().anyMatch(character -> character <= 32 || character >= 127)) { throw new IllegalArgumentException("invalid header name"); }
        return name.toLowerCase(Locale.ROOT);
    }
}
