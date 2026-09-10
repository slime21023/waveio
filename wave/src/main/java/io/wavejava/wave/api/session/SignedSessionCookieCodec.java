package io.wavejava.wave.api.session;

import io.wavejava.wave.api.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA-256 codec for a bounded opaque server-side session identifier cookie. */
public final class SignedSessionCookieCodec {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final byte[] secret;
    private final SessionCookiePolicy policy;

    /** Creates an HMAC-SHA-256 cookie codec with a copied secret and immutable cookie policy. */
    public SignedSessionCookieCodec(byte[] secret, SessionCookiePolicy policy) {
        var suppliedSecret = Objects.requireNonNull(secret, "secret").clone();
        if (suppliedSecret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("session-cookie HMAC secret must contain at least "
                    + MINIMUM_SECRET_BYTES + " bytes");
        }
        this.secret = suppliedSecret;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Returns the exact request/response cookie name owned by this codec. */
    public String cookieName() {
        return policy.name();
    }

    /** Returns immutable cookie rendering and wire-budget policy. */
    public SessionCookiePolicy policy() {
        return policy;
    }

    /** Signs a live session ID and expiry into an HTTP-only response cookie. */
    public Cookie encode(Session session, Instant now) {
        var source = Objects.requireNonNull(session, "session");
        var issuedAt = Objects.requireNonNull(now, "now");
        if (source.isExpired(issuedAt)) {
            throw new IllegalStateException("cannot issue a signed cookie for an expired session");
        }
        var expiresAt = source.expiresAt();
        var payload = VERSION + '.' + source.id().value() + '.' + expiresAt.toEpochMilli();
        var encoded = payload + '.' + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        if (encoded.length() > policy.maximumEncodedBytes()) {
            throw new SessionLimitExceededException(
                    "signed session cookie bytes", policy.maximumEncodedBytes(), encoded.length());
        }
        var builder = Cookie.builder(policy.name(), encoded)
                .path(policy.path())
                .secure(policy.secure())
                .httpOnly(policy.httpOnly())
                .sameSite(policy.sameSite())
                .expires(expiresAt)
                .maxAge(Duration.between(issuedAt, expiresAt));
        return builder.build();
    }

    /** Returns a same-attribute cookie that immediately removes the session identifier client-side. */
    public Cookie clear() {
        return Cookie.builder(policy.name(), "")
                .path(policy.path())
                .secure(policy.secure())
                .httpOnly(policy.httpOnly())
                .sameSite(policy.sameSite())
                .expires(Instant.EPOCH)
                .maxAge(Duration.ZERO)
                .build();
    }

    /**
     * Verifies an untrusted cookie value without exposing parsing or signing failure details.
     *
     * <p>Tampered, malformed, oversized, and expired values all become an empty result so callers
     * can safely begin a new session without making the value an oracle.</p>
     */
    public Optional<SessionCookie> decode(String encoded, Instant now) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > policy.maximumEncodedBytes()) {
            return Optional.empty();
        }
        try {
            var separator = encoded.lastIndexOf('.');
            if (separator <= 0 || separator == encoded.length() - 1) {
                return Optional.empty();
            }
            var payload = encoded.substring(0, separator);
            var actualSignature = Base64.getUrlDecoder().decode(encoded.substring(separator + 1));
            if (!MessageDigest.isEqual(sign(payload), actualSignature)) {
                return Optional.empty();
            }
            var fields = payload.split("\\.", -1);
            if (fields.length != 3 || !VERSION.equals(fields[0])) {
                return Optional.empty();
            }
            var id = SessionId.of(fields[1]);
            var expiresAt = Instant.ofEpochMilli(Long.parseLong(fields[2]));
            if (!Objects.requireNonNull(now, "now").isBefore(expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(new SessionCookie(id, expiresAt));
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return Optional.empty();
        }
    }

    private byte[] sign(String payload) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }
}
