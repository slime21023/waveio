package io.wavejava.wave.api.server;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.Request;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed trust policy for deriving a request's public origin from proxy headers.
 *
 * <p>Forwarded headers are ignored unless the immediate TCP peer is in an explicitly configured
 * numeric CIDR. A malformed, duplicate, multi-hop, or incomplete header is ignored rather than
 * partially interpreted. This class never changes the wire {@link Request#scheme()} or
 * {@link Request#authority()}; it sets only the separately exposed {@link Request#publicAddress()}.
 * That preserves the original transport facts for diagnostics and protocol handling.</p>
 */
public final class ForwardedHeaderPolicy {
    private static final ForwardedHeaderPolicy DISABLED = new ForwardedHeaderPolicy(List.of(), false);

    private final List<Cidr> trustedPeers;
    private final boolean allowLegacyXForwarded;

    private ForwardedHeaderPolicy(List<Cidr> trustedPeers, boolean allowLegacyXForwarded) {
        this.trustedPeers = List.copyOf(trustedPeers);
        this.allowLegacyXForwarded = allowLegacyXForwarded;
    }

    /** Returns the default policy, which trusts no peer and ignores all forwarding headers. */
    public static ForwardedHeaderPolicy disabled() {
        return DISABLED;
    }

    /** Starts a builder for an immutable explicit-trust policy. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns whether the policy has at least one trusted peer range. */
    public boolean isEnabled() {
        return !trustedPeers.isEmpty();
    }

    /** Returns whether legacy {@code X-Forwarded-Proto/Host} parsing is explicitly enabled. */
    public boolean allowsLegacyXForwarded() {
        return allowLegacyXForwarded;
    }

    /**
     * Returns the request unchanged unless its immediate peer is trusted and it contains one
     * unambiguous, complete forwarding origin.
     */
    public Request apply(Request request) {
        var source = Objects.requireNonNull(request, "request");
        if (!isTrusted(source.remoteAddress().orElse(null))) {
            return source;
        }
        var forwarded = parseForwarded(source.headers());
        if (forwarded.state == ParseState.VALID) {
            return source.withPublicAddress(forwarded.address);
        }
        if (forwarded.state == ParseState.INVALID || !allowLegacyXForwarded) {
            return source;
        }
        var legacy = parseLegacy(source.headers());
        return legacy.state == ParseState.VALID ? source.withPublicAddress(legacy.address) : source;
    }

    private boolean isTrusted(SocketAddress peer) {
        if (!(peer instanceof InetSocketAddress socket) || socket.getAddress() == null) {
            return false;
        }
        var address = socket.getAddress();
        return trustedPeers.stream().anyMatch(range -> range.matches(address));
    }

    private static ParsedAddress parseForwarded(Headers headers) {
        var values = headers.all("Forwarded");
        if (values.isEmpty()) {
            return ParsedAddress.absent();
        }
        if (values.size() != 1 || values.getFirst().indexOf(',') >= 0) {
            return ParsedAddress.invalid();
        }
        var parameters = parseParameters(values.getFirst());
        if (parameters.isEmpty()) {
            return ParsedAddress.invalid();
        }
        var proto = parameters.orElseThrow().get("proto");
        var host = parameters.orElseThrow().get("host");
        if (proto == null || host == null) {
            return ParsedAddress.invalid();
        }
        return PublicAddress.parse(proto, host).map(ParsedAddress::valid).orElseGet(ParsedAddress::invalid);
    }

    private static ParsedAddress parseLegacy(Headers headers) {
        var proto = exactlyOne(headers.all("X-Forwarded-Proto"));
        var host = exactlyOne(headers.all("X-Forwarded-Host"));
        if (proto == null && host == null) {
            return ParsedAddress.absent();
        }
        if (proto == null || host == null) {
            return ParsedAddress.invalid();
        }
        return PublicAddress.parse(proto, host).map(ParsedAddress::valid).orElseGet(ParsedAddress::invalid);
    }

    private static String exactlyOne(List<String> values) {
        if (values.size() != 1) {
            return null;
        }
        var value = values.getFirst();
        if (value.isBlank() || value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
            return null;
        }
        return value.trim();
    }

    /** Parses one RFC 7239 element; accepting multiple elements would make hop selection ambiguous. */
    private static Optional<Map<String, String>> parseParameters(String value) {
        var parameters = new LinkedHashMap<String, String>();
        var index = 0;
        while (index < value.length()) {
            while (index < value.length() && value.charAt(index) == ' ') {
                index++;
            }
            var keyStart = index;
            while (index < value.length() && isToken(value.charAt(index))) {
                index++;
            }
            if (keyStart == index || index >= value.length() || value.charAt(index) != '=') {
                return Optional.empty();
            }
            var key = value.substring(keyStart, index).toLowerCase(Locale.ROOT);
            index++;
            final String parameterValue;
            if (index < value.length() && value.charAt(index) == '"') {
                var quoted = new StringBuilder();
                index++;
                while (index < value.length() && value.charAt(index) != '"') {
                    var character = value.charAt(index++);
                    if (character == '\\' || character <= 0x1f || character == 0x7f) {
                        return Optional.empty();
                    }
                    quoted.append(character);
                }
                if (index >= value.length()) {
                    return Optional.empty();
                }
                index++;
                parameterValue = quoted.toString();
            } else {
                var valueStart = index;
                while (index < value.length() && value.charAt(index) != ';') {
                    if (value.charAt(index) == ' ' || value.charAt(index) == '\\') {
                        return Optional.empty();
                    }
                    index++;
                }
                if (valueStart == index) {
                    return Optional.empty();
                }
                parameterValue = value.substring(valueStart, index);
            }
            if (parameters.putIfAbsent(key, parameterValue) != null) {
                return Optional.empty();
            }
            if (index == value.length()) {
                return Optional.of(Map.copyOf(parameters));
            }
            if (value.charAt(index) != ';') {
                return Optional.empty();
            }
            index++;
        }
        return Optional.empty();
    }

    private static boolean isToken(char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9') || character == '-' || character == '_';
    }

    /** Builder for a bounded explicit proxy trust list. */
    public static final class Builder {
        private static final int MAXIMUM_TRUSTED_RANGES = 128;
        private final List<Cidr> trustedPeers = new ArrayList<>();
        private boolean allowLegacyXForwarded;

        private Builder() {
        }

        /** Adds one numeric IPv4 or IPv6 CIDR whose immediate peers may supply forwarding headers. */
        public Builder trustedProxy(String cidr) {
            if (trustedPeers.size() >= MAXIMUM_TRUSTED_RANGES) {
                throw new IllegalStateException("trusted proxy ranges exceed " + MAXIMUM_TRUSTED_RANGES);
            }
            trustedPeers.add(Cidr.parse(cidr));
            return this;
        }

        /** Enables strict one-hop legacy {@code X-Forwarded-Proto/Host} support. */
        public Builder allowLegacyXForwarded(boolean allowLegacyXForwarded) {
            this.allowLegacyXForwarded = allowLegacyXForwarded;
            return this;
        }

        /** Builds the immutable policy; an empty builder yields the disabled policy. */
        public ForwardedHeaderPolicy build() {
            return trustedPeers.isEmpty() ? disabled() : new ForwardedHeaderPolicy(trustedPeers, allowLegacyXForwarded);
        }
    }

    private enum ParseState { ABSENT, INVALID, VALID }

    private record ParsedAddress(ParseState state, PublicAddress address) {
        private static ParsedAddress absent() {
            return new ParsedAddress(ParseState.ABSENT, null);
        }

        private static ParsedAddress invalid() {
            return new ParsedAddress(ParseState.INVALID, null);
        }

        private static ParsedAddress valid(PublicAddress address) {
            return new ParsedAddress(ParseState.VALID, Objects.requireNonNull(address, "address"));
        }
    }

    private record Cidr(byte[] network, int prefixLength) {
        private static Cidr parse(String source) {
            var value = Objects.requireNonNull(source, "cidr");
            var separator = value.lastIndexOf('/');
            if (separator <= 0 || separator != value.indexOf('/') || separator == value.length() - 1) {
                throw new IllegalArgumentException("trusted proxy must be an IPv4 or IPv6 CIDR: " + source);
            }
            var addressPart = value.substring(0, separator);
            if (!addressPart.chars().allMatch(character -> Character.digit(character, 16) >= 0 || character == '.' || character == ':')) {
                throw new IllegalArgumentException("trusted proxy CIDR must use a numeric address: " + source);
            }
            try {
                var network = InetAddress.getByName(addressPart).getAddress();
                var prefix = Integer.parseInt(value.substring(separator + 1));
                if (prefix < 0 || prefix > network.length * Byte.SIZE) {
                    throw new IllegalArgumentException("trusted proxy CIDR prefix is out of range: " + source);
                }
                return new Cidr(network, prefix);
            } catch (UnknownHostException | NumberFormatException failure) {
                throw new IllegalArgumentException("trusted proxy must be an IPv4 or IPv6 CIDR: " + source, failure);
            }
        }

        private boolean matches(InetAddress candidate) {
            var address = candidate.getAddress();
            if (address.length != network.length) {
                return false;
            }
            var remaining = prefixLength;
            for (var index = 0; index < network.length && remaining > 0; index++) {
                var mask = remaining >= Byte.SIZE ? 0xff : 0xff << (Byte.SIZE - remaining);
                if ((network[index] & mask) != (address[index] & mask)) {
                    return false;
                }
                remaining -= Byte.SIZE;
            }
            return true;
        }
    }
}
