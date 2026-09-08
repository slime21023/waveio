package io.waveio.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** An exact origin-form path pattern with literal and named segments. */
public final class RoutePattern {
    private final String pattern;
    private final List<String> segments;
    private RoutePattern(String pattern, List<String> segments) { this.pattern = pattern; this.segments = segments; }
    /** Parses a literal or `{name}` segment pattern. */ public static RoutePattern of(String pattern) {
        Objects.requireNonNull(pattern, "pattern"); if (!pattern.startsWith("/")) { throw new IllegalArgumentException("route pattern must start with /"); }
        List<String> parts = List.of(pattern.substring(1).split("/", -1));
        for (String part : parts) { if (part.startsWith("{") != part.endsWith("}") || (part.startsWith("{") && part.length() < 3)) { throw new IllegalArgumentException("invalid path parameter"); } }
        return new RoutePattern(pattern, parts);
    }
    /** Matches an exact raw path once-decoding allowed segments, or returns no match. */ public Map<String, String> match(String rawPath) {
        Objects.requireNonNull(rawPath, "rawPath"); if (!rawPath.startsWith("/") || rawPath.contains("%2f") || rawPath.contains("%2F")) { return Map.of(); }
        List<String> candidate = List.of(rawPath.substring(1).split("/", -1)); if (candidate.size() != segments.size()) { return Map.of(); }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < segments.size(); index++) {
            String expected = segments.get(index); String actual;
            try { actual = URLDecoder.decode(candidate.get(index), StandardCharsets.UTF_8); } catch (IllegalArgumentException exception) { return Map.of(); }
            if (expected.startsWith("{")) { if (actual.isEmpty()) { return Map.of(); } values.put(expected.substring(1, expected.length() - 1), actual); } else if (!expected.equals(actual)) { return Map.of(); }
        }
        return Map.copyOf(values);
    }
    /** Returns the original route pattern. */ @Override public String toString() { return pattern; }
}
