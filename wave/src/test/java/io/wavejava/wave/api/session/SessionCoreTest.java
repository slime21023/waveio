package io.wavejava.wave.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionCoreTest {
    private static final Instant START = Instant.parse("2026-09-09T00:00:00Z");

    @Test
    void sessionIdsAreOpaqueUrlSafeAndRejectWeakOrInvalidWireValues() {
        var first = SessionId.random();
        var second = SessionId.random();

        assertNotEquals(first, second);
        assertEquals(first, SessionId.of(first.value()));
        assertThrows(IllegalArgumentException.class, () -> SessionId.of("too-short"));
        assertThrows(IllegalArgumentException.class, () -> SessionId.of("a".repeat(21) + "+"));
    }

    @Test
    void attributesAreAtomicallyBoundedByCountAndUtf8Bytes() {
        var policy = new SessionPolicy(Duration.ofMinutes(5), Duration.ofHours(1), Duration.ZERO, 1, 6);
        var session = Session.create(SessionId.random(), policy, START);

        session.put("a", "12345", policy);
        assertThrows(SessionLimitExceededException.class, () -> session.put("b", "1", policy));
        assertEquals("12345", session.attribute("a").orElseThrow(), "rejected write must not partially mutate state");

        session.remove("a");
        assertThrows(SessionLimitExceededException.class, () -> session.put("a", "ééé", policy));
        assertTrue(session.attributes().isEmpty());
    }

    @Test
    void touchRespectsIdleAndAbsoluteExpiryAndRotationPreservesAbsoluteLifetime() {
        var policy = new SessionPolicy(Duration.ofMinutes(5), Duration.ofMinutes(12), Duration.ofMinutes(4), 4, 64);
        var session = Session.create(SessionId.random(), policy, START);
        var original = session.id();

        session.touch(START.plus(Duration.ofMinutes(4)), policy);
        assertEquals(START.plus(Duration.ofMinutes(9)), session.expiresAt());
        assertTrue(session.shouldRotate(START.plus(Duration.ofMinutes(4)), policy));
        var replaced = session.rotate(SessionId.random(), START.plus(Duration.ofMinutes(4)), policy);
        assertEquals(original, replaced);
        assertEquals(START.plus(Duration.ofMinutes(9)), session.expiresAt());

        session.touch(START.plus(Duration.ofMinutes(8)), policy);
        assertEquals(START.plus(Duration.ofMinutes(12)), session.expiresAt(), "absolute expiry must cap an idle refresh");
        assertTrue(session.isExpired(START.plus(Duration.ofMinutes(12))));
    }

    @Test
    void inMemoryStoreSnapshotsAndPurgesExpiredEntriesBeforeCapacityAdmission() {
        var clock = new MutableClock(START);
        var policy = new SessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ZERO, 4, 64);
        var store = new InMemorySessionStore(1, clock);
        var first = Session.create(SessionId.random(), policy, START);
        first.put("role", "reader", policy);
        store.save(first);

        var loaded = store.find(first.id()).orElseThrow();
        loaded.put("role", "writer", policy);
        assertEquals("reader", store.find(first.id()).orElseThrow().attribute("role").orElseThrow(),
                "loaded state must be detached until saved");

        var blockedSecond = Session.create(SessionId.random(), policy, START);
        assertThrows(SessionLimitExceededException.class, () -> store.save(blockedSecond));
        clock.advance(Duration.ofMinutes(1));
        var second = Session.create(SessionId.random(), policy, clock.instant());
        store.save(second);
        assertFalse(store.find(first.id()).isPresent());
        assertTrue(store.find(second.id()).isPresent());
        assertEquals(1, store.size());
    }

    @Test
    void invalidatedSessionsAreNotRetainedOrPersisted() {
        var policy = SessionPolicy.defaults();
        var store = new InMemorySessionStore(2, Clock.fixed(START, ZoneOffset.UTC));
        var session = Session.create(SessionId.random(), policy, START);
        store.save(session);

        session.invalidate();
        store.save(session);
        assertTrue(store.find(session.id()).isEmpty());
        assertEquals(0, store.size());
        assertThrows(IllegalStateException.class, () -> session.put("x", "y", policy));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
