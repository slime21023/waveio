package io.wavejava.wave.api.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ResiliencePolicyTest {
    @Test
    void tokenBucketsRefillAndRejectNewIdentityWhenTheFiniteKeyTableIsFull() {
        var clock = new MutableClock(Instant.parse("2026-09-09T00:00:00Z"));
        var rate = RateLimitPolicy.builder()
                .capacity(1)
                .tokensPerPeriod(1)
                .refillPeriod(Duration.ofSeconds(1))
                .maximumKeys(1)
                .keyIdleTimeout(Duration.ofSeconds(2))
                .keyer(request -> request.header("X-Client").orElse("anonymous"))
                .clock(clock)
                .build();
        var app = Wave.app().middleware(rate).routes(routes ->
                routes.get("/work", (request, response) -> response.text("ok"))).build();

        assertEquals(200, app.handle(request("one")).status());
        var exhausted = app.handle(request("one"));
        assertEquals(429, exhausted.status());
        assertEquals("1", exhausted.headers().first("Retry-After").orElseThrow());
        assertEquals(1, rate.activeKeys());
        assertEquals(429, app.handle(request("two")).status(), "full key table must reject instead of retain more keys");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(200, app.handle(request("one")).status());
        clock.advance(Duration.ofSeconds(2));
        assertEquals(200, app.handle(request("two")).status(), "expired identity is reclaimed before admitting a new key");
        assertEquals(1, rate.activeKeys());
    }

    @Test
    void bulkheadHasNoQueueAndReleasesExactlyOnceForFailureAndNormalUnwind() throws Exception {
        var bulkhead = new BulkheadPolicy(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Wave.app().middleware(bulkhead).routes(routes -> routes.get("/hold", (request, response) -> {
            entered.countDown();
            assertTrue(release.await(1, TimeUnit.SECONDS));
            response.text("released");
        })).build();
        var first = new AtomicReference<io.wavejava.wave.api.http.Response>();
        var worker = Thread.ofVirtual().start(() -> first.set(app.handle(Request.of(HttpMethod.GET, "/hold"))));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(1, bulkhead.activeInvocations());
        assertEquals(503, app.handle(Request.of(HttpMethod.GET, "/hold")).status());
        assertEquals(1, bulkhead.activeInvocations());

        release.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(1));
        assertEquals(200, first.get().status());
        assertEquals(0, bulkhead.activeInvocations());

        var failing = Wave.app().middleware(bulkhead).routes(routes ->
                routes.get("/fail", (request, response) -> {
                    throw new IllegalStateException("application failure");
                })).build();
        assertEquals(500, failing.handle(Request.of(HttpMethod.GET, "/fail")).status());
        assertEquals(0, bulkhead.activeInvocations());
    }

    @Test
    void invalidOrOversizedRateKeysAreRejectedWithoutEnteringTheBucketTable() {
        var policy = RateLimitPolicy.builder()
                .maximumKeyBytes(3)
                .keyer(request -> request.header("X-Client").orElse(""))
                .build();
        var app = Wave.app().middleware(policy).routes(routes ->
                routes.get("/work", (request, response) -> response.text("ok"))).build();

        assertEquals(429, app.handle(request("four")).status());
        assertEquals(0, policy.activeKeys());
        assertFalse(app.handle(request(" ")).headers().all("Retry-After").isEmpty());
    }

    private static Request request(String client) {
        return Request.builder().method(HttpMethod.GET).path("/work")
                .headers(Headers.of("X-Client", client)).build();
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
