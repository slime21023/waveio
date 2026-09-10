package io.wavejava.wave.api.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Parsed route path syntax used while compiling the route tree. */
final class RoutePattern {
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private final String source;
    private final List<Segment> segments;

    private RoutePattern(String source, List<Segment> segments) {
        this.source = source;
        this.segments = List.copyOf(segments);
    }

    static RoutePattern parse(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.isEmpty() || pattern.charAt(0) != '/') {
            throw new IllegalArgumentException("route pattern must start with '/': " + pattern);
        }
        if (pattern.indexOf('?') >= 0 || pattern.indexOf('#') >= 0) {
            throw new IllegalArgumentException("route pattern must not contain a query or fragment: " + pattern);
        }
        if ("/".equals(pattern)) {
            return new RoutePattern(pattern, List.of());
        }

        String[] rawSegments = pattern.substring(1).split("/", -1);
        List<Segment> parsed = new ArrayList<>(rawSegments.length);
        Set<String> captureNames = new LinkedHashSet<>();
        for (int index = 0; index < rawSegments.length; index++) {
            String rawSegment = rawSegments[index];
            if (rawSegment.isEmpty()) {
                throw new IllegalArgumentException("route pattern must not contain an empty segment: " + pattern);
            }
            Segment segment = parseSegment(rawSegment, pattern);
            if (segment.kind() == SegmentKind.WILDCARD && index != rawSegments.length - 1) {
                throw new IllegalArgumentException("a wildcard must be the final route segment: " + pattern);
            }
            if (segment.name() != null && !captureNames.add(segment.name())) {
                throw new IllegalArgumentException("route pattern repeats path parameter '" + segment.name() + "': " + pattern);
            }
            parsed.add(segment);
        }
        return new RoutePattern(pattern, parsed);
    }

    private static Segment parseSegment(String rawSegment, String pattern) {
        if ("*".equals(rawSegment) || "**".equals(rawSegment)) {
            return new Segment(SegmentKind.WILDCARD, null, null);
        }
        if (rawSegment.startsWith("{*") && rawSegment.endsWith("}")) {
            return wildcard(rawSegment.substring(2, rawSegment.length() - 1), pattern);
        }
        if (rawSegment.startsWith("*") && rawSegment.length() > 1) {
            return wildcard(rawSegment.substring(1), pattern);
        }
        if (rawSegment.startsWith("{") || rawSegment.endsWith("}")) {
            if (!rawSegment.startsWith("{") || !rawSegment.endsWith("}")) {
                throw new IllegalArgumentException("invalid path parameter segment '" + rawSegment + "' in " + pattern);
            }
            return parameter(rawSegment.substring(1, rawSegment.length() - 1), pattern);
        }
        if (rawSegment.indexOf('{') >= 0 || rawSegment.indexOf('}') >= 0 || rawSegment.indexOf('*') >= 0) {
            throw new IllegalArgumentException("invalid literal route segment '" + rawSegment + "' in " + pattern);
        }
        return new Segment(SegmentKind.STATIC, rawSegment, null);
    }

    private static Segment parameter(String name, String pattern) {
        validateParameterName(name, pattern);
        return new Segment(SegmentKind.PARAMETER, null, name);
    }

    private static Segment wildcard(String name, String pattern) {
        validateParameterName(name, pattern);
        return new Segment(SegmentKind.WILDCARD, null, name);
    }

    private static void validateParameterName(String name, String pattern) {
        if (!PARAMETER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid path parameter name '" + name + "' in " + pattern);
        }
    }

    static String join(String prefix, String child) {
        RoutePattern parsedPrefix = parse(prefix);
        RoutePattern parsedChild = parse(child);
        if (parsedPrefix.segments.isEmpty()) {
            return parsedChild.source;
        }
        if (parsedChild.segments.isEmpty()) {
            return parsedPrefix.source;
        }
        return parsedPrefix.source + parsedChild.source;
    }

    String source() {
        return source;
    }

    List<Segment> segments() {
        return segments;
    }

    Map<String, String> bind(List<String> captures) {
        Objects.requireNonNull(captures, "captures");
        Map<String, String> values = new LinkedHashMap<>();
        int captureIndex = 0;
        for (Segment segment : segments) {
            if (segment.kind() != SegmentKind.STATIC) {
                if (captureIndex >= captures.size()) {
                    throw new IllegalStateException("route tree produced too few path captures for " + source);
                }
                if (segment.name() != null) {
                    values.put(segment.name(), captures.get(captureIndex));
                }
                captureIndex++;
            }
        }
        if (captureIndex != captures.size()) {
            throw new IllegalStateException("route tree produced too many path captures for " + source);
        }
        return Map.copyOf(values);
    }

    enum SegmentKind {
        STATIC,
        PARAMETER,
        WILDCARD
    }

    record Segment(SegmentKind kind, String literal, String name) {
        Segment {
            Objects.requireNonNull(kind, "kind");
        }
    }
}
