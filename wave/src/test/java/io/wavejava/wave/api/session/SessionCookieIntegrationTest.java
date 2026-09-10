package io.wavejava.wave.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.CookieCodec;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionCookieIntegrationTest {
    private static final Instant START = Instant.parse("2026-09-09T00:00:00Z");

    @Test
    void signedCookieRoundTripPersistsStateAndRotatesTheOldIdentifierAtomically() {
        var clock = new MutableClock(START);
        var store = new InMemorySessionStore(4, clock);
        var policy = new SessionPolicy(Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofMinutes(5), 4, 128);
        var codec = new SignedSessionCookieCodec(new byte[32],
                new SessionCookiePolicy("sid", "/", false, true,
                        io.wavejava.wave.api.http.Cookie.SameSite.LAX, 512));
        var manager = new SessionManager(store, policy, codec, clock);

        var first = manager.open(requestWithCookie(null));
        assertTrue(first.isNew());
        first.session().put("role", "reader", policy);
        var firstResponse = new io.wavejava.wave.internal.http.InternalResponse();
        first.commit(firstResponse);
        var firstCookie = responseCookie(firstResponse);
        var firstId = codec.decode(firstCookie.value(), clock.instant()).orElseThrow().sessionId();
        assertTrue(store.find(firstId).isPresent());

        clock.advance(Duration.ofMinutes(5));
        var resumed = manager.open(requestWithCookie(firstCookie));
        assertFalse(resumed.isNew());
        assertEquals("reader", resumed.session().attribute("role").orElseThrow());
        var resumedResponse = new io.wavejava.wave.internal.http.InternalResponse();
        resumed.commit(resumedResponse);
        var rotatedCookie = responseCookie(resumedResponse);
        var rotatedId = codec.decode(rotatedCookie.value(), clock.instant()).orElseThrow().sessionId();
        assertNotEquals(firstId, rotatedId);
        assertTrue(store.find(firstId).isEmpty(), "old session ID must be atomically invalid after rotation");
        assertEquals("reader", store.find(rotatedId).orElseThrow().attribute("role").orElseThrow());
    }

    @Test
    void tamperedOrExpiredCookiesBecomeNewSessionsWithoutSigningDiagnostics() {
        var clock = new MutableClock(START);
        var policy = new SessionPolicy(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ZERO, 4, 128);
        var codec = new SignedSessionCookieCodec(new byte[32],
                new SessionCookiePolicy("sid", "/", false, true,
                        io.wavejava.wave.api.http.Cookie.SameSite.LAX, 512));
        var manager = new SessionManager(new InMemorySessionStore(4, clock), policy, codec, clock);

        var scope = manager.open(requestWithCookie(null));
        var response = new io.wavejava.wave.internal.http.InternalResponse();
        scope.commit(response);
        var cookie = responseCookie(response);
        var tamperedValue = "x" + cookie.value().substring(1);
        assertTrue(codec.decode(tamperedValue, clock.instant()).isEmpty());
        assertTrue(manager.open(Request.builder().headers(Headers.of("Cookie", "sid=" + tamperedValue)).build()).isNew());

        clock.advance(Duration.ofSeconds(2));
        assertTrue(codec.decode(cookie.value(), clock.instant()).isEmpty());
        assertTrue(manager.open(requestWithCookie(cookie)).isNew());
    }

    @Test
    void invalidationDeletesTheStoreEntryClearsCookieAndCannotRunAfterResponseCommit() {
        var clock = new MutableClock(START);
        var policy = SessionPolicy.defaults();
        var codec = new SignedSessionCookieCodec(new byte[32],
                new SessionCookiePolicy("sid", "/", false, true,
                        io.wavejava.wave.api.http.Cookie.SameSite.LAX, 512));
        var store = new InMemorySessionStore(2, clock);
        var manager = new SessionManager(store, policy, codec, clock);

        var scope = manager.open(requestWithCookie(null));
        var response = new io.wavejava.wave.internal.http.InternalResponse();
        scope.commit(response);
        var issued = responseCookie(response);
        var id = codec.decode(issued.value(), clock.instant()).orElseThrow().sessionId();
        var resumed = manager.open(requestWithCookie(issued));
        var clearResponse = new io.wavejava.wave.internal.http.InternalResponse();
        resumed.invalidate(clearResponse);
        assertTrue(store.find(id).isEmpty());
        assertEquals(Duration.ZERO, responseCookie(clearResponse).maxAge().orElseThrow());

        var tooLate = manager.open(requestWithCookie(null));
        var committed = new io.wavejava.wave.internal.http.InternalResponse().text("already committed");
        assertThrows(IllegalStateException.class, () -> tooLate.commit(committed));
    }

    @Test
    void codecRejectsWeakSecretsAndInvalidCookiePolicies() {
        assertThrows(IllegalArgumentException.class,
                () -> new SignedSessionCookieCodec(new byte[31], SessionCookiePolicy.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionCookiePolicy("sid", "/", false, true,
                        io.wavejava.wave.api.http.Cookie.SameSite.NONE, 256));
    }

    private static Request requestWithCookie(io.wavejava.wave.api.http.Cookie cookie) {
        return cookie == null
                ? Request.of(io.wavejava.wave.api.http.HttpMethod.GET, "/")
                : Request.builder().headers(Headers.of("Cookie", cookie.name() + '=' + cookie.value())).build();
    }

    private static io.wavejava.wave.api.http.Cookie responseCookie(Response response) {
        return CookieCodec.decodeSetCookie(response.headers().first("Set-Cookie").orElseThrow()).orElseThrow();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
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
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
