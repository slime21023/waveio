package io.wavejava.wave.api.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientContractsTest {
    @Test
    void requestOwnsBytesAndClassifiesRetrySafetyConservatively() {
        var body = new byte[] {1, 2, 3};
        var get = ClientRequest.builder().uri(URI.create("https://example.test/items")).body(body).build();
        body[0] = 9;

        assertEquals(1, get.body()[0]);
        assertTrue(get.retrySafe());
        assertFalse(ClientRequest.builder()
                .uri(URI.create("https://example.test/items"))
                .method(HttpMethod.POST)
                .build()
                .retrySafe());
        assertTrue(ClientRequest.builder()
                .uri(URI.create("https://example.test/items"))
                .method(HttpMethod.POST)
                .retrySafe(true)
                .build()
                .retrySafe());
    }

    @Test
    void clientPoolHasFiniteDefaultsAndRejectsInvalidBudgets() {
        var defaults = ClientRequestPool.defaults();

        assertTrue(defaults.maximumConcurrentRequests() > 0);
        assertTrue(defaults.maximumQueuedRequests() >= 0);
        assertTrue(defaults.maximumRequestBodyBytes() > 0);
        assertTrue(defaults.maximumResponseBodyBytes() > 0);
        assertTrue(defaults.maximumResponseHeaders() > 0);
        assertTrue(defaults.maximumResponseHeaderBytes() > 0);
        assertTrue(defaults.connectTimeout().isPositive());
        assertTrue(defaults.requestTimeout().isPositive());
        assertThrows(IllegalArgumentException.class, () -> ClientRequestPool.builder().maximumConcurrentRequests(0));
        assertThrows(IllegalArgumentException.class, () -> ClientRequestPool.builder().maximumQueuedRequests(-1));
        assertThrows(IllegalArgumentException.class, () -> ClientRequestPool.builder().maximumRequestBodyBytes(0));
        assertThrows(IllegalArgumentException.class, () -> ClientRequestPool.builder().maximumResponseBodyBytes(0));
        assertThrows(IllegalArgumentException.class, () -> ClientRequestPool.builder().maximumResponseHeaders(0));
        assertThrows(IllegalArgumentException.class, () -> ClientRequestPool.builder().maximumResponseHeaderBytes(0));
        assertThrows(IllegalArgumentException.class, () -> ClientRequestPool.builder().connectTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ClientRequestPool.builder().requestTimeout(Duration.ofMillis(-1)));
        defaults.close();
    }

    @Test
    void rejectsAnOversizedRequestBeforeItCanEnterTheTransport() {
        var pool = ClientRequestPool.builder().maximumRequestBodyBytes(3).build();
        try (var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build())) {
            var failure = assertThrows(ClientLimitExceededException.class, () -> client.execute(ClientRequest.builder()
                    .uri(URI.create("https://example.test/oversized"))
                    .body(new byte[] {1, 2, 3, 4})
                    .build()));

            assertEquals("request body bytes", failure.limitName());
            assertEquals(3, failure.limit());
        } finally {
            pool.close();
        }
    }

    @Test
    void boundedAdmissionPromotesOneQueuedExchangeWhenTheLeaseReturns() {
        var pool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .maximumQueuedRequests(1)
                .build();
        try {
            var first = pool.acquire().join();
            var queued = pool.acquire();

            assertEquals(new ClientRequestPool.Snapshot(1, 1, false), pool.snapshot());
            assertFalse(queued.isDone());

            first.close();
            var promoted = queued.join();
            assertEquals(new ClientRequestPool.Snapshot(1, 0, false), pool.snapshot());

            promoted.close();
            assertEquals(new ClientRequestPool.Snapshot(0, 0, false), pool.snapshot());
        } finally {
            pool.close();
        }
    }

    @Test
    void cancelledAndClosedWaitersNeverRetainRequestAdmission() {
        var pool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .maximumQueuedRequests(2)
                .build();
        try {
            var first = pool.acquire().join();
            var cancelled = pool.acquire();
            var retained = pool.acquire();
            assertTrue(cancelled.cancel(false));
            assertEquals(new ClientRequestPool.Snapshot(1, 1, false), pool.snapshot());

            first.close();
            var promoted = retained.join();
            assertEquals(new ClientRequestPool.Snapshot(1, 0, false), pool.snapshot());

            var waiting = pool.acquire();
            pool.close();
            var closed = assertThrows(java.util.concurrent.CompletionException.class, waiting::join);
            assertInstanceOf(IllegalStateException.class, closed.getCause());
            assertTrue(pool.isClosed());
            assertThrows(java.util.concurrent.CompletionException.class, () -> pool.acquire().join());
            promoted.close();
            assertEquals(new ClientRequestPool.Snapshot(0, 0, true), pool.snapshot());
        } finally {
            pool.close();
        }
    }

    @Test
    void retryAndRedirectPoliciesAreBoundedAndDoNotPermitUnsafeDefaultReplay() {
        var get = ClientRequest.get(URI.create("https://example.test/items"));
        var post = ClientRequest.builder().uri(URI.create("https://example.test/items")).method(HttpMethod.POST).build();
        var retries = RetryPolicy.builder().maximumAttempts(3).retryOnStatus(503).build();

        assertTrue(retries.shouldRetryResponse(get, 1, 503));
        assertFalse(retries.shouldRetryResponse(post, 1, 503));
        assertFalse(retries.shouldRetryResponse(get, 3, 503));
        assertTrue(RedirectPolicy.normal().maximumRedirects() > 0);
        assertEquals(RedirectPolicy.Mode.NEVER, RedirectPolicy.never().mode());
        assertTrue(ProxyPolicy.direct().isDirect());
        assertEquals(URI.create("http://proxy.example.test:8080"),
                ProxyPolicy.http(URI.create("http://proxy.example.test:8080")).endpoint().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> ProxyPolicy.http(URI.create("https://proxy.example.test")));
    }

    @Test
    void backoffIsFiniteAndCapped() {
        var policy = BackoffPolicy.exponential(Duration.ofMillis(10), Duration.ofMillis(25));

        assertEquals(Duration.ofMillis(10), policy.delayBeforeRetry(1));
        assertEquals(Duration.ofMillis(20), policy.delayBeforeRetry(2));
        assertEquals(Duration.ofMillis(25), policy.delayBeforeRetry(3));
        assertEquals(Duration.ofMillis(25), policy.delayBeforeRetry(10));
        assertThrows(IllegalArgumentException.class, () -> policy.delayBeforeRetry(0));
    }
}


