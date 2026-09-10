package io.wavejava.wave.api.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A validated public HTTP origin selected for an incoming request.
 *
 * <p>The value is deliberately limited to {@code http}/{@code https}, a host, and a concrete
 * port. It is safe to derive redirect, absolute-link, cookie, and WebSocket origins from this
 * value; it must not be built by concatenating untrusted forwarding headers.</p>
 */
public final class PublicAddress {
    private final String scheme;
    private final String host;
    private final int port;

    private PublicAddress(String scheme, String host, int port) {
        this.scheme = normalizeScheme(scheme);
        this.host = normalizeHost(host);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
        }
        this.port = port;
    }

    /** Creates one validated HTTP or HTTPS public origin. */
    public static PublicAddress of(String scheme, String host, int port) {
        return new PublicAddress(scheme, host, port);
    }

    /**
     * Parses a complete HTTP authority, using the scheme's default port when the authority omits
     * one. Invalid, user-info-bearing, path-bearing, or non-HTTP values produce an empty result.
     */
    public static Optional<PublicAddress> parse(String scheme, String authority) {
        try {
            var normalizedScheme = normalizeScheme(scheme);
            var rawAuthority = Objects.requireNonNull(authority, "authority");
            if (rawAuthority.isBlank() || rawAuthority.indexOf('\\') >= 0 || containsControl(rawAuthority)) {
                return Optional.empty();
            }
            var uri = new URI(normalizedScheme + "://" + rawAuthority);
            if (uri.getRawUserInfo() != null || uri.getHost() == null || uri.getRawPath() == null
                    || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return Optional.empty();
            }
            var parsedPort = uri.getPort();
            return Optional.of(new PublicAddress(
                    normalizedScheme,
                    uri.getHost(),
                    parsedPort < 0 ? defaultPort(normalizedScheme) : parsedPort));
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            return Optional.empty();
        }
    }

    /** Returns the normalized public scheme. */
    public String scheme() {
        return scheme;
    }

    /** Returns the normalized host name or IP literal without IPv6 brackets. */
    public String host() {
        return host;
    }

    /** Returns the explicit effective port. */
    public int port() {
        return port;
    }

    /** Returns a safe HTTP authority, omitting the scheme's default port. */
    public String authority() {
        var renderedHost = host.indexOf(':') >= 0 ? '[' + host + ']' : host;
        return port == defaultPort(scheme) ? renderedHost : renderedHost + ':' + port;
    }

    /** Returns the public URI origin without a trailing slash. */
    public URI origin() {
        return URI.create(scheme + "://" + authority());
    }

    /** Resolves an origin-form target against this public origin. */
    public URI resolve(String target) {
        var value = Objects.requireNonNull(target, "target");
        if (!value.startsWith("/") || value.startsWith("//") || containsControl(value)) {
            throw new IllegalArgumentException("target must be a safe origin-form path");
        }
        return origin().resolve(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PublicAddress address
                && scheme.equals(address.scheme) && host.equals(address.host) && port == address.port;
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, host, port);
    }

    @Override
    public String toString() {
        return origin().toString();
    }

    private static String normalizeScheme(String scheme) {
        var value = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        if (!value.equals("http") && !value.equals("https")) {
            throw new IllegalArgumentException("public address scheme must be http or https: " + scheme);
        }
        return value;
    }

    private static String normalizeHost(String host) {
        var value = Objects.requireNonNull(host, "host");
        if (value.startsWith("[") || value.endsWith("]")) {
            if (value.length() < 3 || !value.startsWith("[") || !value.endsWith("]")) {
                throw new IllegalArgumentException("IPv6 host brackets must be balanced");
            }
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank() || containsControl(value) || value.indexOf('@') >= 0 || value.indexOf('/') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("host must be a safe non-blank host or IP literal");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static int defaultPort(String scheme) {
        return scheme.equals("https") ? 443 : 80;
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character <= 0x1f || character == 0x7f);
    }
}
