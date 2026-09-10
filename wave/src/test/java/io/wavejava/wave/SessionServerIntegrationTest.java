package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.Cookie;
import io.wavejava.wave.api.http.CookieCodec;
import io.wavejava.wave.api.session.InMemorySessionStore;
import io.wavejava.wave.api.session.SessionCookiePolicy;
import io.wavejava.wave.api.session.SessionManager;
import io.wavejava.wave.api.session.SessionPolicy;
import io.wavejava.wave.api.session.SignedSessionCookieCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionServerIntegrationTest {
    @Test
    void explicitSessionScopePersistsAndRotatesSignedCookieBeforeRealSocketResponseCommit() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-09-09T00:00:00Z"));
        var policy = new SessionPolicy(Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofMinutes(5), 4, 256);
        var store = new InMemorySessionStore(8, clock);
        var codec = new SignedSessionCookieCodec(new byte[32], new SessionCookiePolicy(
                "sid", "/", false, true, Cookie.SameSite.LAX, 512));
        var sessions = new SessionManager(store, policy, codec, clock);
        var app = Wave.app().routes(routes -> routes.get("/counter", (request, response) -> {
            var scope = sessions.open(request);
            var current = Integer.parseInt(scope.session().attribute("count").orElse("0"));
            scope.session().put("count", Integer.toString(current + 1), policy);
            scope.commit(response);
            response.text(Integer.toString(current + 1));
        })).build();
        var client = HttpClient.newHttpClient();

        try (var server = Wave.server(app).listen(0).start()) {
            var first = send(client, server.port(), null);
            assertEquals(200, first.statusCode());
            assertEquals("1", first.body());
            var firstCookie = CookieCodec.decodeSetCookie(first.headers().firstValue("Set-Cookie").orElseThrow()).orElseThrow();
            var firstId = codec.decode(firstCookie.value(), clock.instant()).orElseThrow().sessionId();

            clock.advance(Duration.ofMinutes(5));
            var second = send(client, server.port(), firstCookie);
            assertEquals(200, second.statusCode());
            assertEquals("2", second.body());
            var rotatedCookie = CookieCodec.decodeSetCookie(second.headers().firstValue("Set-Cookie").orElseThrow()).orElseThrow();
            var rotatedId = codec.decode(rotatedCookie.value(), clock.instant()).orElseThrow().sessionId();
            assertNotEquals(firstId, rotatedId);
            assertTrue(store.find(firstId).isEmpty());
            assertEquals("2", store.find(rotatedId).orElseThrow().attribute("count").orElseThrow());
        }
    }

    private static HttpResponse<String> send(HttpClient client, int port, Cookie cookie) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/counter")).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie.name() + '=' + cookie.value());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
