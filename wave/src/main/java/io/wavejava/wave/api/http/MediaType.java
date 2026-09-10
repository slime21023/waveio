package io.wavejava.wave.api.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable HTTP media type, including optional parameters.
 *
 * <p>Type, subtype, and parameter names are compared case-insensitively. Parameter values preserve
 * their supplied value after quoted-string unescaping.</p>
 */
public final class MediaType {
    public static final MediaType APPLICATION_JSON = of("application", "json");
    public static final MediaType APPLICATION_PROBLEM_JSON = of("application", "problem+json");
    public static final MediaType APPLICATION_OCTET_STREAM = of("application", "octet-stream");
    public static final MediaType APPLICATION_FORM_URLENCODED = of("application", "x-www-form-urlencoded");
    public static final MediaType TEXT_PLAIN = of("text", "plain");
    public static final MediaType TEXT_EVENT_STREAM = of("text", "event-stream");
    public static final MediaType TEXT_PLAIN_UTF_8 = TEXT_PLAIN.withCharset(StandardCharsets.UTF_8);

    private final String type;
    private final String subtype;
    private final Map<String, String> parameters;

    private MediaType(String type, String subtype, Map<String, String> parameters) {
        this.type = type;
        this.subtype = subtype;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /** Creates a media type with no parameters. */
    public static MediaType of(String type, String subtype) {
        return new MediaType(normalizeToken(type, "type", true), normalizeToken(subtype, "subtype", true), Map.of());
    }

    /** Parses a {@code Content-Type} or media-range value. */
    public static MediaType parse(String value) {
        Objects.requireNonNull(value, "value");
        var parts = split(value);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Media type must not be empty");
        }

        var slash = parts.getFirst().indexOf('/');
        if (slash <= 0 || slash != parts.getFirst().lastIndexOf('/') || slash == parts.getFirst().length() - 1) {
            throw new IllegalArgumentException("Media type must contain one type/subtype separator: " + value);
        }

        var type = normalizeToken(parts.getFirst().substring(0, slash).trim(), "type", true);
        var subtype = normalizeToken(parts.getFirst().substring(slash + 1).trim(), "subtype", true);
        var parameters = new LinkedHashMap<String, String>();
        for (var index = 1; index < parts.size(); index++) {
            var part = parts.get(index).trim();
            var equals = part.indexOf('=');
            if (equals <= 0 || equals == part.length() - 1) {
                throw new IllegalArgumentException("Invalid media type parameter: " + part);
            }
            var name = normalizeToken(part.substring(0, equals).trim(), "parameter name", false);
            var parameterValue = parseParameterValue(part.substring(equals + 1).trim());
            if (parameters.putIfAbsent(name, parameterValue) != null) {
                throw new IllegalArgumentException("Duplicate media type parameter: " + name);
            }
        }
        return new MediaType(type, subtype, parameters);
    }

    /** Returns the normalized primary type, for example {@code application}. */
    public String type() {
        return type;
    }

    /** Returns the normalized subtype, for example {@code json}. */
    public String subtype() {
        return subtype;
    }

    /** Returns an immutable mapping of normalized parameter names to values. */
    public Map<String, String> parameters() {
        return parameters;
    }

    /** Returns a parameter value by name, ignoring ASCII case. */
    public Optional<String> parameter(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(parameters.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Returns the declared charset, if present. */
    public Optional<Charset> charset() {
        return parameter("charset").map(Charset::forName);
    }

    /** Returns a copy with a normalized {@code charset} parameter. */
    public MediaType withCharset(Charset charset) {
        Objects.requireNonNull(charset, "charset");
        return withParameter("charset", charset.name());
    }

    /** Returns a copy with {@code name} set to {@code value}. */
    public MediaType withParameter(String name, String value) {
        var normalizedName = normalizeToken(name, "parameter name", false);
        var normalizedValue = validateParameterValue(value);
        var copy = new LinkedHashMap<>(parameters);
        copy.put(normalizedName, normalizedValue);
        return new MediaType(type, subtype, copy);
    }

    /** Returns a copy without {@code name}. */
    public MediaType withoutParameter(String name) {
        Objects.requireNonNull(name, "name");
        var copy = new LinkedHashMap<>(parameters);
        copy.remove(name.toLowerCase(Locale.ROOT));
        return new MediaType(type, subtype, copy);
    }

    /**
     * Determines whether this media type matches a requested media range. Wildcards are supported on
     * the requested range, including structured suffix ranges such as {@code application/*+json}.
     */
    public boolean matches(MediaType requestedRange) {
        Objects.requireNonNull(requestedRange, "requestedRange");
        return (requestedRange.type.equals("*") || requestedRange.type.equals(type))
                && subtypeMatches(requestedRange.subtype, subtype);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MediaType mediaType
                && type.equals(mediaType.type)
                && subtype.equals(mediaType.subtype)
                && parameters.equals(mediaType.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, subtype, parameters);
    }

    @Override
    public String toString() {
        var rendered = new StringBuilder(type).append('/').append(subtype);
        parameters.forEach((name, value) -> rendered.append("; ").append(name).append('=').append(renderParameterValue(value)));
        return rendered.toString();
    }

    private static boolean subtypeMatches(String requested, String actual) {
        if (requested.equals("*")) {
            return true;
        }
        if (requested.startsWith("*+")) {
            return actual.endsWith(requested.substring(1));
        }
        return requested.equals(actual);
    }

    private static String normalizeToken(String value, String label, boolean allowWildcard) {
        Objects.requireNonNull(value, label);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Media type " + label + " must not be empty");
        }
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '*' && allowWildcard) {
                continue;
            }
            if (!HttpMethod.isTokenCharacter(character)) {
                throw new IllegalArgumentException("Invalid media type " + label + ": " + value);
            }
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static List<String> split(String value) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        var escaped = false;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '\r' || character == '\n') {
                throw new IllegalArgumentException("Media type must not contain line breaks");
            }
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (quoted && character == '\\') {
                current.append(character);
                escaped = true;
            } else if (character == '"') {
                current.append(character);
                quoted = !quoted;
            } else if (character == ';' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted || escaped) {
            throw new IllegalArgumentException("Unterminated quoted media type parameter: " + value);
        }
        parts.add(current.toString());
        return parts;
    }

    private static String parseParameterValue(String value) {
        if (value.startsWith("\"")) {
            if (value.length() < 2 || !value.endsWith("\"")) {
                throw new IllegalArgumentException("Unterminated quoted media type parameter: " + value);
            }
            var unescaped = new StringBuilder(value.length() - 2);
            var escaped = false;
            for (var index = 1; index < value.length() - 1; index++) {
                var character = value.charAt(index);
                if (escaped) {
                    unescaped.append(character);
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"' || character == '\r' || character == '\n') {
                    throw new IllegalArgumentException("Invalid quoted media type parameter: " + value);
                } else {
                    unescaped.append(character);
                }
            }
            if (escaped) {
                throw new IllegalArgumentException("Invalid quoted media type parameter: " + value);
            }
            return validateParameterValue(unescaped.toString());
        }
        if (!isToken(value)) {
            throw new IllegalArgumentException("Media type parameter value must be a token or quoted string: " + value);
        }
        return value;
    }

    private static String validateParameterValue(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Media type parameter value must not be empty");
        }
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == 0) {
                throw new IllegalArgumentException("Media type parameter value contains an unsafe character");
            }
        }
        return value;
    }

    private static boolean isToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (var index = 0; index < value.length(); index++) {
            if (!HttpMethod.isTokenCharacter(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String renderParameterValue(String value) {
        if (isToken(value)) {
            return value;
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
