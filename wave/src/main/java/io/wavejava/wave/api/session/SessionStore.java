package io.wavejava.wave.api.session;

import java.util.Optional;

/**
 * Bounded persistence boundary for server-side sessions.
 *
 * <p>Implementations must return detached snapshots, reject or otherwise explicitly handle their
 * capacity limit, and never retain an expired or invalidated session indefinitely.</p>
 */
public interface SessionStore {
    /** Loads a detached, non-expired session snapshot, if the identifier is still valid. */
    Optional<Session> find(SessionId id);

    /** Stores a detached snapshot, replacing the entry with the same identifier if one exists. */
    void save(Session session);

    /**
     * Atomically replaces an existing identifier with a rotated snapshot.
     *
     * <p>On success the prior identifier can no longer resolve. A {@code false} result means the
     * prior entry was absent or expired, so a manager must not issue a new cookie for this stale
     * scope.</p>
     */
    boolean rotate(SessionId previousId, Session replacement);

    /** Deletes one session, returning whether a live entry existed. */
    boolean delete(SessionId id);

    /** Returns the current live-entry count after any opportunistic expiry cleanup. */
    int size();
}
