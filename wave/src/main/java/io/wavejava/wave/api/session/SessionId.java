package io.wavejava.wave.api.session;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque, URL-safe identifier for one server-side session. */
public final class SessionId {
    private static final int MINIMUM_LENGTH = 22;
    private static final int MAXIMUM_LENGTH = 128;
    private static final Pattern URL_SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private SessionId(String value) {
        this.value = value;
    }

    /** Creates a cryptographically random 256-bit session identifier. */
    public static SessionId random() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new SessionId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** Parses one already-issued opaque session identifier. */
    public static SessionId of(String value) {
        var candidate = Objects.requireNonNull(value, "value");
        if (candidate.length() < MINIMUM_LENGTH || candidate.length() > MAXIMUM_LENGTH
                || !URL_SAFE_TOKEN.matcher(candidate).matches()) {
            throw new IllegalArgumentException("session ID must be a " + MINIMUM_LENGTH + "-" + MAXIMUM_LENGTH
                    + " character URL-safe opaque token");
        }
        return new SessionId(candidate);
    }

    /** Returns the URL-safe opaque wire value. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionId id && value.equals(id.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
