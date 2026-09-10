package io.wavejava.wave.api.form;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable URL-encoded form multimap.
 *
 * <p>Field occurrences retain wire order in {@link #entries()}, while {@link #values(String)}
 * preserves the order of repeated values for one name. Instances produced by
 * {@link UrlEncodedFormParser} are bounded by that parser's limits.</p>
 */
/** Immutable key/value data produced by parsing an {@code application/x-www-form-urlencoded} body. */
public final class FormData {
    private static final FormData EMPTY = new FormData(List.of());

    private final List<Entry> entries;
    private final Map<String, List<String>> values;

    private FormData(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        var grouped = new LinkedHashMap<String, List<String>>();
        for (var entry : entries) {
            grouped.computeIfAbsent(entry.name(), ignored -> new ArrayList<>()).add(entry.value());
        }
        var immutable = new LinkedHashMap<String, List<String>>();
        grouped.forEach((name, fieldValues) -> immutable.put(name, List.copyOf(fieldValues)));
        values = Collections.unmodifiableMap(immutable);
    }

    /** Returns an empty form. */
    public static FormData empty() {
        return EMPTY;
    }

    static FormData fromEntries(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        return entries.isEmpty() ? EMPTY : new FormData(entries);
    }

    /** Returns every field occurrence in received order. */
    public List<Entry> entries() {
        return entries;
    }

    /** Returns the number of field occurrences, including repeated names. */
    public int size() {
        return entries.size();
    }

    /** Returns whether at least one field has {@code name}. */
    public boolean contains(String name) {
        return values.containsKey(Objects.requireNonNull(name, "name"));
    }

    /** Returns field names in first-occurrence order. */
    public Set<String> names() {
        return values.keySet();
    }

    /** Returns every value for {@code name} in received order. */
    public List<String> values(String name) {
        return values.getOrDefault(Objects.requireNonNull(name, "name"), List.of());
    }

    /** Returns the first value for {@code name}, if present. */
    public Optional<String> first(String name) {
        var fieldValues = values(name);
        return fieldValues.isEmpty() ? Optional.empty() : Optional.of(fieldValues.getFirst());
    }

    /** Returns an immutable mapping of field names to repeated values. */
    public Map<String, List<String>> asMap() {
        return values;
    }

    /** One decoded name/value occurrence. */
    public record Entry(String name, String value) {
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }
}
