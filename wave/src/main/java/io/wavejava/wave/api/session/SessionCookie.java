package io.wavejava.wave.api.session;

import java.time.Instant;
import java.util.Objects;

/** A successfully verified, non-expired signed session-cookie payload. */
public record SessionCookie(SessionId sessionId, Instant expiresAt) {
    public SessionCookie {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
