package io.wavejava.wave.api.health;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded health result safe to expose through an operations endpoint. */
public final class HealthStatus {
    /** Coarse health state used by liveness, readiness, and individual checks. */
    public enum State {
        UP,
        DOWN
    }

    private static final int MAXIMUM_SUMMARY_BYTES = 256;
    private static final int MAXIMUM_DETAIL_ENTRIES = 16;
    private static final int MAXIMUM_DETAIL_BYTES = 4 * 1024;

    private final State state;
    private final String summary;
    private final Map<String, String> details;

    private HealthStatus(State state, String summary, Map<String, String> details) {
        this.state = Objects.requireNonNull(state, "state");
        this.summary = validateSummary(summary);
        this.details = validateDetails(details);
    }

    /** Creates an available status with a bounded human-readable summary. */
    public static HealthStatus up(String summary) {
        return new HealthStatus(State.UP, summary, Map.of());
    }

    /** Creates an unavailable status with a bounded human-readable summary. */
    public static HealthStatus down(String summary) {
        return new HealthStatus(State.DOWN, summary, Map.of());
    }

    /** Returns the coarse available/unavailable state. */
    public State state() {
        return state;
    }

    /** Returns a safe summary without exception messages or arbitrary payloads. */
    public String summary() {
        return summary;
    }

    /** Returns an immutable bounded detail snapshot. */
    public Map<String, String> details() {
        return details;
    }

    /** Returns a new status with one bounded diagnostic detail. */
    public HealthStatus withDetail(String key, String value) {
        var merged = new LinkedHashMap<>(details);
        merged.put(validateDetailKey(key), validateDetailValue(value));
        return new HealthStatus(state, summary, merged);
    }

    /** Returns whether this status is available. */
    public boolean isUp() {
        return state == State.UP;
    }

    private static String validateSummary(String summary) {
        var value = Objects.requireNonNull(summary, "summary");
        if (value.isBlank() || utf8Bytes(value) > MAXIMUM_SUMMARY_BYTES || containsControl(value)) {
            throw new IllegalArgumentException("health summary must be non-blank, safe, and at most "
                    + MAXIMUM_SUMMARY_BYTES + " UTF-8 bytes");
        }
        return value;
    }

    private static Map<String, String> validateDetails(Map<String, String> details) {
        Objects.requireNonNull(details, "details");
        if (details.size() > MAXIMUM_DETAIL_ENTRIES) {
            throw new IllegalArgumentException("health details exceed " + MAXIMUM_DETAIL_ENTRIES + " entries");
        }
        long bytes = 0;
        var copy = new LinkedHashMap<String, String>();
        for (var entry : details.entrySet()) {
            var key = validateDetailKey(entry.getKey());
            var value = validateDetailValue(entry.getValue());
            bytes = Math.addExact(bytes, utf8Bytes(key));
            bytes = Math.addExact(bytes, utf8Bytes(value));
            if (bytes > MAXIMUM_DETAIL_BYTES) {
                throw new IllegalArgumentException("health details exceed " + MAXIMUM_DETAIL_BYTES + " UTF-8 bytes");
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String validateDetailKey(String key) {
        var value = Objects.requireNonNull(key, "detail key");
        if (value.isBlank() || utf8Bytes(value) > 128 || containsControl(value)) {
            throw new IllegalArgumentException("health detail key must be safe, non-blank, and at most 128 UTF-8 bytes");
        }
        return value;
    }

    private static String validateDetailValue(String value) {
        var candidate = Objects.requireNonNull(value, "detail value");
        if (utf8Bytes(candidate) > MAXIMUM_DETAIL_BYTES || containsControl(candidate)) {
            throw new IllegalArgumentException("health detail value must be safe and bounded");
        }
        return candidate;
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character == '\r' || character == '\n' || character == '\0');
    }
}
