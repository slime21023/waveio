package io.wavejava.wave.api.session;

import io.wavejava.wave.api.http.Request;
import java.time.Clock;
import java.util.Objects;

/**
 * Opens explicit request scopes that persist bounded server-side sessions before response commit.
 *
 * <p>Wave's {@code Response} cannot accept a new {@code Set-Cookie} after it is committed, so the
 * scope deliberately makes persistence explicit: application code calls {@link SessionScope#commit}
 * before choosing {@code text()}, {@code json()}, {@code stream()}, or another response writer.
 * This avoids a hidden post-response hook that could silently lose session rotation or expiry.</p>
 */
public final class SessionManager {
    private final SessionStore store;
    private final SessionPolicy sessionPolicy;
    private final SignedSessionCookieCodec cookieCodec;
    private final Clock clock;

    /** Creates a manager with explicit storage, lifetime, signed-cookie, and clock dependencies. */
    public SessionManager(
            SessionStore store,
            SessionPolicy sessionPolicy,
            SignedSessionCookieCodec cookieCodec,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessionPolicy = Objects.requireNonNull(sessionPolicy, "sessionPolicy");
        this.cookieCodec = Objects.requireNonNull(cookieCodec, "cookieCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Opens one request-owned scope, resolving a valid signed cookie or lazily creating a new session. */
    public SessionScope open(Request request) {
        var source = Objects.requireNonNull(request, "request");
        var now = clock.instant();
        var decoded = source.cookie(cookieCodec.cookieName())
                .flatMap(cookie -> cookieCodec.decode(cookie.value(), now));
        if (decoded.isPresent()) {
            var stored = store.find(decoded.orElseThrow().sessionId());
            if (stored.isPresent() && !stored.orElseThrow().isExpired(now)) {
                return new SessionScope(this, stored.orElseThrow(), decoded.orElseThrow().sessionId(), false);
            }
        }
        return new SessionScope(this, Session.create(SessionId.random(), sessionPolicy, now), null, true);
    }

    SessionPolicy sessionPolicy() {
        return sessionPolicy;
    }

    SignedSessionCookieCodec cookieCodec() {
        return cookieCodec;
    }

    Clock clock() {
        return clock;
    }

    SessionStore store() {
        return store;
    }
}
