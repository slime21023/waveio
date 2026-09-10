package io.wavejava.wave.api.session;

import io.wavejava.wave.api.http.Response;
import java.time.Instant;
import java.util.Objects;

/** One explicit request-owned session persistence scope created by {@link SessionManager}. */
public final class SessionScope {
    private final SessionManager manager;
    private final Session session;
    private final SessionId originalId;
    private final boolean isNew;
    private boolean completed;

    SessionScope(SessionManager manager, Session session, SessionId originalId, boolean isNew) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.session = Objects.requireNonNull(session, "session");
        this.originalId = originalId;
        this.isNew = isNew;
    }

    /** Returns the mutable request-owned session state. */
    public Session session() {
        return session;
    }

    /** Returns whether no trusted persisted session was associated with this request. */
    public boolean isNew() {
        return isNew;
    }

    /**
     * Persists or deletes this scope, then appends its signed cookie before response commitment.
     *
     * @throws IllegalStateException if the response is already committed or this scope was used
     */
    public synchronized void commit(Response response) {
        var target = Objects.requireNonNull(response, "response");
        if (completed) {
            throw new IllegalStateException("session scope has already completed");
        }
        if (target.isCommitted()) {
            throw new IllegalStateException("session scope must commit before the HTTP response is committed");
        }
        completed = true;
        var now = manager.clock().instant();
        if (session.isInvalidated() || session.isExpired(now)) {
            deleteKnownIdentifiers();
            target.cookie(manager.cookieCodec().clear());
            return;
        }

        session.touch(now, manager.sessionPolicy());
        if (session.isInvalidated()) {
            deleteKnownIdentifiers();
            target.cookie(manager.cookieCodec().clear());
            return;
        }

        if (!isNew && session.shouldRotate(now, manager.sessionPolicy())) {
            var previous = session.rotate(SessionId.random(), now, manager.sessionPolicy());
            if (!manager.store().rotate(previous, session)) {
                // The storage entry disappeared between opening and committing. Do not mint a
                // cookie for state that no longer has a trusted server-side record. Delete both
                // identifiers defensively so an implementation that reported a stale rotation
                // cannot leave the prior credential reusable until natural expiry.
                session.invalidate();
                deleteKnownIdentifiers();
                target.cookie(manager.cookieCodec().clear());
                return;
            }
        } else {
            manager.store().save(session);
        }
        target.cookie(manager.cookieCodec().encode(session, now));
    }

    /** Invalidates this session and commits its deletion/clear-cookie operation. */
    public synchronized void invalidate(Response response) {
        session.invalidate();
        commit(response);
    }

    private void deleteKnownIdentifiers() {
        if (originalId != null) {
            manager.store().delete(originalId);
        }
        manager.store().delete(session.id());
    }
}
