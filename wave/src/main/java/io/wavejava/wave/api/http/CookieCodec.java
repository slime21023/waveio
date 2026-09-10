package io.wavejava.wave.api.http;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Stateless parser and renderer for request {@code Cookie} and response {@code Set-Cookie} fields. */
public final class CookieCodec {
    private static final DateTimeFormatter EXPIRES_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private CookieCodec() {
    }

    /** Renders one response cookie as a {@code Set-Cookie} field value. */
    public static String encodeSetCookie(Cookie cookie) {
        return Objects.requireNonNull(cookie, "cookie").toSetCookieHeader();
    }

    /** Decodes every valid name/value pair from one request {@code Cookie} field value. */
    public static List<Cookie> decodeRequestHeader(String header) {
        Objects.requireNonNull(header, "header");
        var cookies = new ArrayList<Cookie>();
        for (var pair : header.split(";")) {
            var separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            try {
                cookies.add(Cookie.of(pair.substring(0, separator).trim(), pair.substring(separator + 1).trim()));
            } catch (IllegalArgumentException ignored) {
                // One malformed client pair must not poison unrelated cookies.
            }
        }
        return List.copyOf(cookies);
    }

    /** Decodes all request cookies across every {@code Cookie} header in wire order. */
    public static List<Cookie> decodeRequestHeaders(Headers headers) {
        Objects.requireNonNull(headers, "headers");
        var cookies = new ArrayList<Cookie>();
        headers.all("Cookie").forEach(header -> cookies.addAll(decodeRequestHeader(header)));
        return List.copyOf(cookies);
    }

    /**
     * Decodes a single {@code Set-Cookie} field including supported standard attributes.
     *
     * <p>Unknown extension attributes are ignored. A malformed known attribute makes the entire
     * field invalid instead of silently changing its security semantics.</p>
     */
    public static Optional<Cookie> decodeSetCookie(String header) {
        Objects.requireNonNull(header, "header");
        var pieces = header.split(";", -1);
        if (pieces.length == 0) {
            return Optional.empty();
        }
        var separator = pieces[0].indexOf('=');
        if (separator <= 0) {
            return Optional.empty();
        }
        try {
            var builder = Cookie.builder(pieces[0].substring(0, separator).trim(), pieces[0].substring(separator + 1).trim());
            for (var index = 1; index < pieces.length; index++) {
                var attribute = pieces[index].trim();
                if (attribute.isEmpty()) {
                    continue;
                }
                var equals = attribute.indexOf('=');
                var name = (equals < 0 ? attribute : attribute.substring(0, equals)).trim().toLowerCase(Locale.ROOT);
                var value = equals < 0 ? null : attribute.substring(equals + 1).trim();
                switch (name) {
                    case "secure" -> requireNoValue(name, value, builder::secure);
                    case "httponly" -> requireNoValue(name, value, builder::httpOnly);
                    case "domain" -> builder.domain(requireValue(name, value));
                    case "path" -> builder.path(requireValue(name, value));
                    case "max-age" -> builder.maxAge(Duration.ofSeconds(parseNonNegativeLong(requireValue(name, value), name)));
                    case "expires" -> builder.expires(parseExpires(requireValue(name, value)));
                    case "samesite" -> builder.sameSite(Cookie.SameSite.of(requireValue(name, value)));
                    default -> {
                        // Cookie extensions are intentionally not modeled by the immutable core type.
                    }
                }
            }
            return Optional.of(builder.build());
        } catch (IllegalArgumentException | DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static void requireNoValue(String name, String value, java.util.function.Consumer<Boolean> setter) {
        if (value != null) {
            throw new IllegalArgumentException(name + " must not have a value");
        }
        setter.accept(true);
    }

    private static String requireValue(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " requires a value");
        }
        return value;
    }

    private static long parseNonNegativeLong(String value, String name) {
        var parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return parsed;
    }

    private static Instant parseExpires(String value) {
        return Instant.from(EXPIRES_FORMAT.parse(value));
    }
}
