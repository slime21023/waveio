package io.waveio.http;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable HTTP cookie representation adhering to RFC 6265bis security recommendations.
 *
 * <p>Validates cookie names and values against strict character set specifications to prevent
 * response splitting and injection vulnerabilities. Enforces the security invariant that
 * {@code SameSite=None} cookies must also be marked {@code Secure}.
 *
 * @see #builder(String, String)
 * @see #toSetCookieHeader()
 */
public final class Cookie {

    /**
     * Controls the cross-site request context behavior of the cookie.
     */
    public enum SameSite {
        /** Sent only in first-party contexts. */
        STRICT,
        /** Sent with top-level navigations from external sites. */
        LAX,
        /** Sent in all cross-site contexts; requires {@code Secure} to be true. */
        NONE
    }

    private final String name;
    private final String value;
    private final String path;
    private final String domain;
    private final Duration maxAge;
    private final boolean secure;
    private final boolean httpOnly;
    private final SameSite sameSite;

    private Cookie(Builder builder) {
        name = validateName(builder.name);
        value = validateValue(builder.value);
        path = validateAttribute("path", builder.path);
        domain = validateAttribute("domain", builder.domain);
        maxAge = builder.maxAge;
        secure = builder.secure;
        httpOnly = builder.httpOnly;
        sameSite = builder.sameSite;
        if (sameSite == SameSite.NONE && !secure) {
            throw new IllegalArgumentException("SameSite=None cookies must be Secure");
        }
    }

    /**
     * Creates a new cookie builder with the required name and value.
     *
     * @param name non-blank cookie name (must match RFC 6265 token characters)
     * @param value cookie value (must contain only valid ASCII characters, excluding semicolons and commas)
     * @return a new {@link Builder} instance
     */
    public static Builder builder(String name, String value) {
        return new Builder(name, value);
    }

    /**
     * Returns the name of the cookie.
     *
     * @return cookie name
     */
    public String name() { return name; }

    /**
     * Returns the value of the cookie.
     *
     * @return cookie value
     */
    public String value() { return value; }

    /**
     * Serializes this cookie into a valid {@code Set-Cookie} HTTP header value string.
     *
     * @return serialized Set-Cookie header string
     */
    public String toSetCookieHeader() {
        var header = new StringBuilder(name).append('=').append(value);
        if (path != null) header.append("; Path=").append(path);
        if (domain != null) header.append("; Domain=").append(domain);
        if (maxAge != null) header.append("; Max-Age=").append(maxAge.toSeconds());
        if (secure) header.append("; Secure");
        if (httpOnly) header.append("; HttpOnly");
        if (sameSite != null) {
            String encoded = sameSite.name().charAt(0)
                    + sameSite.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            header.append("; SameSite=").append(encoded);
        }
        return header.toString();
    }

    private static String validateName(String value) {
        Objects.requireNonNull(value, "name");
        if (value.isEmpty() || !value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Invalid cookie name");
        }
        return value;
    }

    private static String validateValue(String value) {
        Objects.requireNonNull(value, "value");
        if (value.chars().anyMatch(character -> character < 0x21 || character > 0x7e
                || character == ';' || character == ',' || character == '"' || character == '\\')) {
            throw new IllegalArgumentException("Invalid cookie value");
        }
        return value;
    }

    private static String validateAttribute(String name, String value) {
        if (value != null && (value.isBlank() || value.indexOf(';') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("Invalid cookie " + name);
        }
        return value;
    }

    /**
     * Fluent builder for constructing immutable {@link Cookie} instances.
     */
    public static final class Builder {
        private final String name;
        private final String value;
        private String path;
        private String domain;
        private Duration maxAge;
        private boolean secure;
        private boolean httpOnly;
        private SameSite sameSite;

        private Builder(String name, String value) {
            this.name = name;
            this.value = value;
        }

        /**
         * Sets the cookie {@code Path} attribute.
         *
         * @param value path attribute value
         * @return this builder
         */
        public Builder path(String value) { path = value; return this; }

        /**
         * Sets the cookie {@code Domain} attribute.
         *
         * @param value domain attribute value
         * @return this builder
         */
        public Builder domain(String value) { domain = value; return this; }

        /**
         * Sets the cookie {@code Secure} flag.
         *
         * @param value whether the cookie requires HTTPS
         * @return this builder
         */
        public Builder secure(boolean value) { secure = value; return this; }

        /**
         * Sets the cookie {@code HttpOnly} flag to prevent client-side script access.
         *
         * @param value whether client-side script access is blocked
         * @return this builder
         */
        public Builder httpOnly(boolean value) { httpOnly = value; return this; }

        /**
         * Sets the cookie {@code SameSite} attribute.
         *
         * @param value the {@link SameSite} policy
         * @return this builder
         */
        public Builder sameSite(SameSite value) { sameSite = value; return this; }

        /**
         * Sets the cookie lifetime via the {@code Max-Age} attribute.
         *
         * @param value non-negative duration representing cookie lifetime
         * @return this builder
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public Builder maxAge(Duration value) {
            if (value != null && value.isNegative()) {
                throw new IllegalArgumentException("Cookie maxAge must not be negative");
            }
            maxAge = value;
            return this;
        }

        /**
         * Builds an immutable {@link Cookie} instance.
         *
         * @return a new {@code Cookie}
         * @throws IllegalArgumentException if attributes fail validation
         */
        public Cookie build() { return new Cookie(this); }
    }
}
