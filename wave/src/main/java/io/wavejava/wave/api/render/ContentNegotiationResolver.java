package io.wavejava.wave.api.render;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Selects one offered response media type from HTTP {@code Accept} header values.
 *
 * <p>The resolver is deterministic. It applies these rules in order:</p>
 *
 * <ol>
 *   <li>An absent or blank {@code Accept} header selects the first offered media type.</li>
 *   <li>For each offered type, its most-specific matching range determines its quality. A more
 *       specific {@code q=0} range excludes that type even when a less-specific range accepts it.</li>
 *   <li>Among acceptable offers, higher quality wins, followed by range specificity, number of
 *       media parameters, source header order, then offered-list order.</li>
 *   <li>Parameters before {@code q} are required to match the offered type. Parameters after
 *       {@code q} are Accept extensions and do not constrain the offer.</li>
 * </ol>
 *
 * <p>Malformed media ranges, quality values, wildcards in an offered type, and empty components
 * in a non-blank header are rejected with {@link IllegalArgumentException}. Quality values use
 * the HTTP grammar {@code 0}, {@code 0.xxx}, {@code 1}, or {@code 1.000}.</p>
 */
public final class ContentNegotiationResolver {
    private static final Pattern QUALITY = Pattern.compile("(?:0(?:\\.\\d{0,3})?|1(?:\\.0{0,3})?)");

    /** Resolves the {@code Accept} values in {@code requestHeaders}. */
    public Optional<MediaType> resolve(Headers requestHeaders, List<MediaType> offered) {
        Objects.requireNonNull(requestHeaders, "requestHeaders");
        return resolve(requestHeaders.all("Accept"), offered);
    }

    /** Resolves ordered {@code Accept} header field values. */
    public Optional<MediaType> resolve(List<String> acceptHeaderValues, List<MediaType> offered) {
        Objects.requireNonNull(acceptHeaderValues, "acceptHeaderValues");
        var offers = validateOffers(offered);
        var ranges = parseRanges(acceptHeaderValues);
        if (offers.isEmpty()) {
            return Optional.empty();
        }

        if (ranges.isEmpty()) {
            return Optional.of(offers.getFirst());
        }

        Candidate selected = null;
        for (var offerIndex = 0; offerIndex < offers.size(); offerIndex++) {
            var offer = offers.get(offerIndex);
            AcceptRange best = null;
            for (var range : ranges) {
                if (matches(offer, range) && (best == null || hasHigherPrecedence(range, best))) {
                    best = range;
                }
            }
            if (best != null && best.quality > 0) {
                var candidate = new Candidate(offer, offerIndex, best);
                if (selected == null || isBetter(candidate, selected)) {
                    selected = candidate;
                }
            }
        }
        return selected == null ? Optional.empty() : Optional.of(selected.offer);
    }

    /** Resolves one {@code Accept} header field value. */
    public Optional<MediaType> resolve(String acceptHeaderValue, List<MediaType> offered) {
        return resolve(List.of(Objects.requireNonNull(acceptHeaderValue, "acceptHeaderValue")), offered);
    }

    private static List<MediaType> validateOffers(List<MediaType> offered) {
        Objects.requireNonNull(offered, "offered");
        var copy = List.copyOf(offered);
        for (var offer : copy) {
            if (offer.type().contains("*") || offer.subtype().contains("*")) {
                throw new IllegalArgumentException("An offered media type must be concrete: " + offer);
            }
        }
        return copy;
    }

    private static List<AcceptRange> parseRanges(List<String> acceptHeaderValues) {
        var ranges = new ArrayList<AcceptRange>();
        var order = 0;
        for (var headerValue : acceptHeaderValues) {
            Objects.requireNonNull(headerValue, "acceptHeaderValues must not contain null");
            if (headerValue.isBlank()) {
                continue;
            }
            for (var component : splitHeaderValue(headerValue)) {
                ranges.add(parseRange(component, order++));
            }
        }
        return List.copyOf(ranges);
    }

    private static List<String> splitHeaderValue(String headerValue) {
        var components = new ArrayList<String>();
        var component = new StringBuilder();
        var quoted = false;
        var escaped = false;
        for (var index = 0; index < headerValue.length(); index++) {
            var character = headerValue.charAt(index);
            if (character == '\r' || character == '\n') {
                throw new IllegalArgumentException("Accept header must not contain line breaks");
            }
            if (escaped) {
                component.append(character);
                escaped = false;
            } else if (quoted && character == '\\') {
                component.append(character);
                escaped = true;
            } else if (character == '"') {
                component.append(character);
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                addComponent(components, component, headerValue);
            } else {
                component.append(character);
            }
        }
        if (quoted || escaped) {
            throw new IllegalArgumentException("Unterminated quoted Accept value: " + headerValue);
        }
        addComponent(components, component, headerValue);
        return components;
    }

    private static void addComponent(List<String> components, StringBuilder component, String headerValue) {
        var value = component.toString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Accept header contains an empty media range: " + headerValue);
        }
        components.add(value);
        component.setLength(0);
    }

    private static AcceptRange parseRange(String component, int order) {
        final MediaType mediaRange;
        try {
            mediaRange = MediaType.parse(component);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid Accept media range: " + component, failure);
        }
        validateMediaRange(mediaRange, component);

        var requiredParameters = new LinkedHashMap<String, String>();
        var quality = 1_000;
        var foundQuality = false;
        for (var parameter : mediaRange.parameters().entrySet()) {
            if (parameter.getKey().equals("q")) {
                quality = parseQuality(parameter.getValue(), component);
                foundQuality = true;
            } else if (!foundQuality) {
                requiredParameters.put(parameter.getKey(), parameter.getValue());
            }
        }
        return new AcceptRange(mediaRange, quality, specificity(mediaRange), requiredParameters.size(), order,
                Map.copyOf(requiredParameters));
    }

    private static void validateMediaRange(MediaType mediaRange, String component) {
        var type = mediaRange.type();
        var subtype = mediaRange.subtype();
        if (type.contains("*") && !type.equals("*")) {
            throw new IllegalArgumentException("Invalid wildcard in Accept media range: " + component);
        }
        if (type.equals("*") && !subtype.equals("*")) {
            throw new IllegalArgumentException("A wildcard Accept type requires wildcard subtype: " + component);
        }
        if (subtype.contains("*")
                && !subtype.equals("*")
                && !(subtype.startsWith("*+") && subtype.length() > 2 && subtype.indexOf('*', 1) < 0)) {
            throw new IllegalArgumentException("Invalid wildcard in Accept media range: " + component);
        }
    }

    private static int parseQuality(String value, String component) {
        if (!QUALITY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Accept quality value '" + value + "' in " + component);
        }
        if (value.charAt(0) == '1') {
            return 1_000;
        }
        var decimalPoint = value.indexOf('.');
        if (decimalPoint < 0) {
            return 0;
        }
        var fraction = value.substring(decimalPoint + 1);
        return Integer.parseInt((fraction + "000").substring(0, 3));
    }

    private static int specificity(MediaType mediaRange) {
        if (mediaRange.type().equals("*")) {
            return 0;
        }
        if (mediaRange.subtype().equals("*")) {
            return 3;
        }
        return mediaRange.subtype().startsWith("*+") ? 4 : 5;
    }

    private static boolean matches(MediaType offer, AcceptRange range) {
        if (!offer.matches(range.mediaRange)) {
            return false;
        }
        for (var required : range.requiredParameters.entrySet()) {
            if (offer.parameter(required.getKey()).filter(required.getValue()::equals).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasHigherPrecedence(AcceptRange candidate, AcceptRange current) {
        if (candidate.specificity != current.specificity) {
            return candidate.specificity > current.specificity;
        }
        if (candidate.parameterCount != current.parameterCount) {
            return candidate.parameterCount > current.parameterCount;
        }
        return candidate.order < current.order;
    }

    private static boolean isBetter(Candidate candidate, Candidate current) {
        if (candidate.range.quality != current.range.quality) {
            return candidate.range.quality > current.range.quality;
        }
        if (candidate.range.specificity != current.range.specificity) {
            return candidate.range.specificity > current.range.specificity;
        }
        if (candidate.range.parameterCount != current.range.parameterCount) {
            return candidate.range.parameterCount > current.range.parameterCount;
        }
        if (candidate.range.order != current.range.order) {
            return candidate.range.order < current.range.order;
        }
        return candidate.offerIndex < current.offerIndex;
    }

    private record AcceptRange(
            MediaType mediaRange,
            int quality,
            int specificity,
            int parameterCount,
            int order,
            Map<String, String> requiredParameters
    ) {
    }

    private record Candidate(MediaType offer, int offerIndex, AcceptRange range) {
    }
}
