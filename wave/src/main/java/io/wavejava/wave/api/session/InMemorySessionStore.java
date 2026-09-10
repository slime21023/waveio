package io.wavejava.wave.api.session;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Finite, synchronized in-memory session store for development and single-process deployments.
 *
 * <p>It has no background sweeper and no unbounded eviction queue: expired entries are removed
 * during every public operation, while a full live store rejects a new identifier explicitly.</p>
 */
public final class InMemorySessionStore implements SessionStore {
    private static final int DEFAULT_MAXIMUM_SESSIONS = 10_000;

    private final int maximumSessions;
    private final Clock clock;
    private final Map<SessionId, Session> sessions = new LinkedHashMap<>();

    /** Creates a store with a finite default capacity and the UTC system clock. */
    public InMemorySessionStore() {
        this(DEFAULT_MAXIMUM_SESSIONS, Clock.systemUTC());
    }

    /** Creates a store with the supplied finite capacity and clock. */
    public InMemorySessionStore(int maximumSessions, Clock clock) {
        if (maximumSessions <= 0) {
            throw new IllegalArgumentException("maximumSessions must be greater than zero");
        }
        this.maximumSessions = maximumSessions;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns this store's finite maximum number of live sessions. */
    public int maximumSessions() {
        return maximumSessions;
    }

    @Override
    public synchronized Optional<Session> find(SessionId id) {
        var key = Objects.requireNonNull(id, "id");
        var candidate = sessions.get(key);
        if (candidate == null) {
            return Optional.empty();
        }
        if (candidate.isExpired(clock.instant())) {
            sessions.remove(key);
            return Optional.empty();
        }
        return Optional.of(candidate.snapshot());
    }

    @Override
    public synchronized void save(Session session) {
        var snapshot = Objects.requireNonNull(session, "session").snapshot();
        var now = clock.instant();
        purgeExpired(now);
        var id = snapshot.id();
        if (snapshot.isExpired(now)) {
            sessions.remove(id);
            return;
        }
        if (!sessions.containsKey(id) && sessions.size() >= maximumSessions) {
            throw new SessionLimitExceededException("in-memory session count", maximumSessions, sessions.size() + 1L);
        }
        sessions.put(id, snapshot);
    }

    @Override
    public synchronized boolean rotate(SessionId previousId, Session replacement) {
        var previous = Objects.requireNonNull(previousId, "previousId");
        var snapshot = Objects.requireNonNull(replacement, "replacement").snapshot();
        var now = clock.instant();
        purgeExpired(now);
        if (snapshot.isExpired(now) || !sessions.containsKey(previous)) {
            return false;
        }
        var replacementId = snapshot.id();
        if (previous.equals(replacementId)) {
            throw new IllegalArgumentException("rotated replacement must have a different session ID");
        }
        if (sessions.containsKey(replacementId)) {
            throw new IllegalStateException("rotated session ID already exists");
        }
        sessions.remove(previous);
        sessions.put(replacementId, snapshot);
        return true;
    }

    @Override
    public synchronized boolean delete(SessionId id) {
        purgeExpired(clock.instant());
        return sessions.remove(Objects.requireNonNull(id, "id")) != null;
    }

    @Override
    public synchronized int size() {
        purgeExpired(clock.instant());
        return sessions.size();
    }

    private void purgeExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }
}
