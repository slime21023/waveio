package io.waveio.http.internal.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RoutePattern {
    private final String value;
    private final List<Segment> segments;

    public RoutePattern(String value) {
        this.value = normalize(value);
        segments = parse(this.value);
    }

    public String value() { return value; }

    public int specificityCost() {
        return segments.stream().mapToInt(segment -> switch (segment.kind) {
            case STATIC -> 0;
            case PARAMETER -> 1;
            case WILDCARD -> 100;
        }).sum();
    }

    public boolean conflictsWith(RoutePattern other) {
        if (segments.size() != other.segments.size()) return false;
        for (int index = 0; index < segments.size(); index++) {
            var left = segments.get(index);
            var right = other.segments.get(index);
            if (left.kind != right.kind) return false;
            if (left.kind == SegmentKind.STATIC && !left.value.equals(right.value)) return false;
        }
        return true;
    }

    public Map<String, String> match(String requestPath) {
        var requestSegments = split(requestPath);
        boolean wildcard = !segments.isEmpty() && segments.getLast().kind == SegmentKind.WILDCARD;
        if ((!wildcard && requestSegments.size() != segments.size())
                || (wildcard && requestSegments.size() < segments.size() - 1)) return null;
        var parameters = new HashMap<String, String>();
        for (int index = 0; index < segments.size(); index++) {
            var expected = segments.get(index);
            if (expected.kind == SegmentKind.WILDCARD) {
                parameters.put(expected.value,
                        String.join("/", requestSegments.subList(index, requestSegments.size())));
                break;
            }
            var actual = requestSegments.get(index);
            if (expected.kind == SegmentKind.PARAMETER) parameters.put(expected.value, actual);
            else if (!expected.value.equals(actual)) return null;
        }
        return parameters;
    }

    public static String join(String prefix, String path) {
        Objects.requireNonNull(path, "path");
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            throw new IllegalArgumentException("Route prefix must start with '/'");
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with '/'");
        }
        String left = prefix.equals("/") ? "" : trimTrailingSlash(prefix);
        String right = path.equals("/") ? "" : trimTrailingSlash(path);
        String joined = left + right;
        return joined.isEmpty() ? "/" : normalize(joined);
    }

    private static List<Segment> parse(String pattern) {
        var names = new java.util.HashSet<String>();
        var values = split(pattern);
        var result = new ArrayList<Segment>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            SegmentKind kind = value.startsWith(":") ? SegmentKind.PARAMETER
                    : value.startsWith("*") ? SegmentKind.WILDCARD : SegmentKind.STATIC;
            String name = kind == SegmentKind.STATIC ? value : value.substring(1);
            if (kind == SegmentKind.WILDCARD && name.isEmpty()) name = "*";
            if (name.isBlank()) throw new IllegalArgumentException("Empty path parameter in " + pattern);
            if (kind != SegmentKind.STATIC && !names.add(name)) {
                throw new IllegalArgumentException("Duplicate path parameter '" + name + "' in " + pattern);
            }
            if (kind == SegmentKind.WILDCARD && index != values.size() - 1) {
                throw new IllegalArgumentException("Wildcard must be the last route segment in " + pattern);
            }
            result.add(new Segment(name, kind));
        }
        return List.copyOf(result);
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with '/'");
        }
        return trimTrailingSlash(path);
    }

    private static String trimTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
    }

    private static List<String> split(String path) {
        if (path.equals("/")) return List.of();
        return List.of(trimTrailingSlash(path).substring(1).split("/", -1));
    }

    private enum SegmentKind { STATIC, PARAMETER, WILDCARD }
    private record Segment(String value, SegmentKind kind) {}
}
