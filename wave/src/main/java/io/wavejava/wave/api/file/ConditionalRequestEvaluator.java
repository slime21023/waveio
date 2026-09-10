package io.wavejava.wave.api.file;

import io.wavejava.wave.api.http.Request;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Evaluates the conditional request subset needed by bounded GET/HEAD static files. */
public final class ConditionalRequestEvaluator {
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private ConditionalRequestEvaluator() {
    }

    /** Result of evaluating cache validators for a selected resource representation. */
    public enum Result {
        PROCEED,
        NOT_MODIFIED
    }

    /**
     * Evaluates {@code If-None-Match}, then (only when absent) {@code If-Modified-Since}.
     *
     * <p>Weak ETag comparison is appropriate for GET/HEAD cache validation. Invalid dates are
     * ignored as required for a robust client-facing parser.</p>
     */
    public static Result evaluate(Request request, String etag, Instant lastModified) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(etag, "etag");
        Objects.requireNonNull(lastModified, "lastModified");

        var ifNoneMatch = request.header("If-None-Match");
        if (ifNoneMatch.isPresent()) {
            return matchesIfNoneMatch(ifNoneMatch.orElseThrow(), etag) ? Result.NOT_MODIFIED : Result.PROCEED;
        }
        var ifModifiedSince = request.header("If-Modified-Since").flatMap(ConditionalRequestEvaluator::parseHttpDate);
        if (ifModifiedSince.isPresent()
                && !lastModified.truncatedTo(ChronoUnit.SECONDS).isAfter(ifModifiedSince.orElseThrow())) {
            return Result.NOT_MODIFIED;
        }
        return Result.PROCEED;
    }

    /** Formats an instant as an IMF-fixdate HTTP header value, rounded down to seconds. */
    public static String formatHttpDate(Instant instant) {
        return HTTP_DATE.format(Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.SECONDS));
    }

    /** Parses an HTTP date, returning empty for an invalid client value. */
    public static Optional<Instant> parseHttpDate(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return Optional.of(Instant.from(HTTP_DATE.parse(value.trim())));
        } catch (DateTimeParseException failure) {
            return Optional.empty();
        }
    }

    private static boolean matchesIfNoneMatch(String header, String etag) {
        for (var candidate : header.split(",")) {
            var normalized = candidate.trim();
            if ("*".equals(normalized) || stripWeakPrefix(normalized).equals(stripWeakPrefix(etag))) {
                return true;
            }
        }
        return false;
    }

    private static String stripWeakPrefix(String value) {
        return value.regionMatches(true, 0, "W/", 0, 2) ? value.substring(2).trim() : value;
    }
}
