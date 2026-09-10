package io.wavejava.wave.api.http;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable HTTP cookie suitable for use in a {@code Set-Cookie} response header.
 *
 * <p>Request cookies contain only a name and value. Attributes are retained when a cookie is built
 * for a response and are rendered by {@link #toSetCookieHeader()}.</p>
 */
public final class Cookie {
    private static final DateTimeFormatter EXPIRES_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final String name;
    private final String value;
    private final String domain;
    private final String path;
    private final Duration maxAge;
    private final Instant expires;
    private final boolean secure;
    private final boolean httpOnly;
    private final SameSite sameSite;

    private Cookie(Builder builder) {
        name = validateName(builder.name);
        value = validateValue(builder.value);
        domain = builder.domain;
        path = builder.path;
        maxAge = builder.maxAge;
        expires = builder.expires;
        secure = builder.secure;
        httpOnly = builder.httpOnly;
        sameSite = builder.sameSite;
    }

    /** Creates a cookie with only a name and value. */
    public static Cookie of(String name, String value) {
        return builder(name, value).build();
    }

    /** Starts construction of a cookie with a required name and value. */
    public static Builder builder(String name, String value) {
        return new Builder(name, value);
    }

    /** Returns the cookie name. */
    public String name() {
        return name;
    }

    /** Returns the cookie value. */
    public String value() {
        return value;
    }

    /** Returns the optional Domain attribute. */
    public Optional<String> domain() {
        return Optional.ofNullable(domain);
    }

    /** Returns the optional Path attribute. */
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /** Returns the optional non-negative Max-Age value. */
    public Optional<Duration> maxAge() {
        return Optional.ofNullable(maxAge);
    }

    /** Returns the optional Expires value. */
    public Optional<Instant> expires() {
        return Optional.ofNullable(expires);
    }

    /** Returns whether the Secure attribute is set. */
    public boolean secure() {
        return secure;
    }

    /** Returns whether the HttpOnly attribute is set. */
    public boolean httpOnly() {
        return httpOnly;
    }

    /** Returns the optional SameSite attribute. */
    public Optional<SameSite> sameSite() {
        return Optional.ofNullable(sameSite);
    }

    /** Renders this cookie as a value for a {@code Set-Cookie} response header. */
    public String toSetCookieHeader() {
        var rendered = new StringBuilder(name).append('=').append(value);
        if (path != null) {
            rendered.append("; Path=").append(path);
        }
        if (domain != null) {
            rendered.append("; Domain=").append(domain);
        }
        if (maxAge != null) {
            rendered.append("; Max-Age=").append(maxAge.toSeconds());
        }
        if (expires != null) {
            rendered.append("; Expires=").append(EXPIRES_FORMAT.format(expires));
        }
        if (secure) {
            rendered.append("; Secure");
        }
        if (httpOnly) {
            rendered.append("; HttpOnly");
        }
        if (sameSite != null) {
            rendered.append("; SameSite=").append(sameSite.wireName);
        }
        return rendered.toString();
    }

    /**
     * Parses a request {@code Cookie} header into name/value cookies.
     *
     * <p>Malformed pairs are ignored rather than making one invalid client header fail an otherwise
     * valid request. The order from the header is retained.</p>
     */
    public static List<Cookie> parseRequestHeader(String header) {
        return CookieCodec.decodeRequestHeader(header);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Cookie cookie
                && name.equals(cookie.name)
                && value.equals(cookie.value)
                && Objects.equals(domain, cookie.domain)
                && Objects.equals(path, cookie.path)
                && Objects.equals(maxAge, cookie.maxAge)
                && Objects.equals(expires, cookie.expires)
                && secure == cookie.secure
                && httpOnly == cookie.httpOnly
                && sameSite == cookie.sameSite;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value, domain, path, maxAge, expires, secure, httpOnly, sameSite);
    }

    @Override
    public String toString() {
        return toSetCookieHeader();
    }

    private static String validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Cookie name must not be empty");
        }
        for (var index = 0; index < name.length(); index++) {
            if (!HttpMethod.isTokenCharacter(name.charAt(index))) {
                throw new IllegalArgumentException("Invalid cookie name: " + name);
            }
        }
        return name;
    }

    private static String validateValue(String value) {
        Objects.requireNonNull(value, "value");
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character <= 0x20 || character == ';' || character == ',' || character == '\\'
                    || character == '"' || character == 0x7f) {
                throw new IllegalArgumentException("Invalid cookie value");
            }
        }
        return value;
    }

    /** Values accepted by the SameSite cookie attribute. */
    public enum SameSite {
        STRICT("Strict"),
        LAX("Lax"),
        NONE("None");

        private final String wireName;

        SameSite(String wireName) {
            this.wireName = wireName;
        }

        /** Parses a SameSite value ignoring ASCII case. */
        public static SameSite of(String value) {
            Objects.requireNonNull(value, "value");
            for (var sameSite : values()) {
                if (sameSite.wireName.equalsIgnoreCase(value)) {
                    return sameSite;
                }
            }
            throw new IllegalArgumentException("Unsupported SameSite value: " + value);
        }

        @Override
        public String toString() {
            return wireName;
        }
    }

    /** Builder for {@link Cookie}. A builder is not thread-safe. */
    public static final class Builder {
        private final String name;
        private final String value;
        private String domain;
        private String path;
        private Duration maxAge;
        private Instant expires;
        private boolean secure;
        private boolean httpOnly;
        private SameSite sameSite;

        private Builder(String name, String value) {
            this.name = name;
            this.value = value;
        }

        /** Sets the Domain attribute. */
        public Builder domain(String domain) {
            Objects.requireNonNull(domain, "domain");
            if (domain.isBlank() || containsUnsafeCharacter(domain)) {
                throw new IllegalArgumentException("Invalid cookie domain");
            }
            this.domain = domain.toLowerCase(Locale.ROOT);
            return this;
        }

        /** Sets the Path attribute. */
        public Builder path(String path) {
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/") || containsUnsafeCharacter(path)) {
                throw new IllegalArgumentException("Cookie path must start with '/' and contain no control characters");
            }
            this.path = path;
            return this;
        }

        /** Sets a non-negative Max-Age duration. */
        public Builder maxAge(Duration maxAge) {
            Objects.requireNonNull(maxAge, "maxAge");
            if (maxAge.isNegative()) {
                throw new IllegalArgumentException("Cookie Max-Age must not be negative");
            }
            this.maxAge = maxAge;
            return this;
        }

        /** Sets the Expires attribute. */
        public Builder expires(Instant expires) {
            this.expires = Objects.requireNonNull(expires, "expires");
            return this;
        }

        /** Sets or clears the Secure attribute. */
        public Builder secure(boolean secure) {
            this.secure = secure;
            return this;
        }

        /** Sets or clears the HttpOnly attribute. */
        public Builder httpOnly(boolean httpOnly) {
            this.httpOnly = httpOnly;
            return this;
        }

        /** Sets the SameSite attribute. */
        public Builder sameSite(SameSite sameSite) {
            this.sameSite = Objects.requireNonNull(sameSite, "sameSite");
            return this;
        }

        /** Creates an immutable cookie. */
        public Cookie build() {
            return new Cookie(this);
        }
    }

    private static boolean containsUnsafeCharacter(String value) {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character <= 0x20 || character == ';' || character == '\r' || character == '\n' || character == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
