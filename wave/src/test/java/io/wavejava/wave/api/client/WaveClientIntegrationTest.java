package io.wavejava.wave.api.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.testing.MockUpstream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class WaveClientIntegrationTest {
    @Test
    void reusesOneSharedTransportConnectionForSequentialRequests() {
        var peers = ConcurrentHashMap.<SocketAddress>newKeySet();
        var app = Wave.app().routes(routes -> routes.get("/reuse", (request, response) -> {
            peers.add(request.remoteAddress().orElseThrow());
            response.text("ok");
        })).build();

        try (var server = Wave.server(app).listen(0).start(); var client = io.wavejava.wave.Wave.client()) {
            var uri = baseUri(server.port()).resolve("/reuse");

            assertEquals("ok", client.execute(ClientRequest.get(uri)).text());
            assertEquals("ok", client.execute(ClientRequest.get(uri)).text());

            assertEquals(1, peers.size(), "one reusable JDK transport should preserve the HTTP/1.1 connection");
        }
    }

    @Test
    void retriesConfiguredTransientGetButNeverRetriesPostWithoutExplicitSafetyOptIn() {
        var getAttempts = new AtomicInteger();
        var postAttempts = new AtomicInteger();
        var app = Wave.app().routes(routes -> {
            routes.get("/retry", (request, response) -> {
                if (getAttempts.incrementAndGet() == 1) {
                    response.status(503).text("retry");
                } else {
                    response.text("recovered");
                }
            });
            routes.post("/unsafe", (request, response) -> {
                postAttempts.incrementAndGet();
                response.status(503).text("do not replay");
            });
        }).build();
        var retryPolicy = RetryPolicy.builder()
                .maximumAttempts(3)
                .retryOnStatus(503)
                .backoff(BackoffPolicy.none())
                .build();

        try (var server = Wave.server(app).listen(0).start();
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().retryPolicy(retryPolicy).build())) {
            var base = baseUri(server.port());
            var recovered = client.execute(ClientRequest.get(base.resolve("/retry")));
            var unsafe = client.execute(ClientRequest.builder()
                    .uri(base.resolve("/unsafe"))
                    .method(HttpMethod.POST)
                    .text("payment")
                    .build());

            assertEquals(200, recovered.status());
            assertEquals("recovered", recovered.text());
            assertEquals(2, getAttempts.get());
            assertEquals(503, unsafe.status());
            assertEquals(1, postAttempts.get(), "POST must not be replayed without retrySafe(true)");
        }
    }

    @Test
    void followsOnlyConfiguredSafeRedirectsAndReportsFinalUri() {
        var app = Wave.app().routes(routes -> {
            routes.get("/from", (request, response) -> response.redirect(302, "/to"));
            routes.get("/to", (request, response) -> response.text("redirected"));
        }).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().redirectPolicy(RedirectPolicy.normal()).build())) {
            var base = baseUri(server.port());
            var response = client.execute(ClientRequest.get(base.resolve("/from")));

            assertEquals(200, response.status());
            assertEquals("redirected", response.text());
            assertEquals(base.resolve("/to"), response.uri());
            assertEquals(1, response.redirectsFollowed());
        }
    }

    @Test
    void redirectDoesNotConsumeTheRetryBudgetForATransientTargetResponse() {
        var targetAttempts = new AtomicInteger();
        var retryPolicy = RetryPolicy.builder()
                .maximumAttempts(2)
                .retryOnStatus(503)
                .backoff(BackoffPolicy.none())
                .build();

        try (var upstream = MockUpstream.start(request -> switch (request.target()) {
                    case "/from" -> MockUpstream.Response.of(
                            302, Headers.of("Location", "/flaky"), new byte[0]);
                    case "/flaky" -> targetAttempts.incrementAndGet() == 1
                            ? MockUpstream.Response.text(503, "retry target")
                            : MockUpstream.Response.text(200, "recovered after redirect");
                    default -> MockUpstream.Response.empty(404);
                });
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder()
                        .redirectPolicy(RedirectPolicy.normal())
                        .retryPolicy(retryPolicy)
                        .build())) {
            var response = client.execute(ClientRequest.get(upstream.baseUri().resolve("/from")));

            assertEquals(200, response.status());
            assertEquals("recovered after redirect", response.text());
            assertEquals(upstream.baseUri().resolve("/flaky"), response.uri());
            assertEquals(1, response.redirectsFollowed());
            assertEquals(2, targetAttempts.get(), "the target receives its initial attempt and one retry");
            assertEquals(3, upstream.requestCount(), "one redirect plus two retry-budget attempts");
            upstream.assertHealthy();
        }
    }

    @Test
    void retryBudgetRemainsBoundedAfterRedirectHops() {
        var targetAttempts = new AtomicInteger();
        var retryPolicy = RetryPolicy.builder()
                .maximumAttempts(2)
                .retryOnStatus(503)
                .backoff(BackoffPolicy.none())
                .build();

        try (var upstream = MockUpstream.start(request -> switch (request.target()) {
                    case "/first" -> MockUpstream.Response.of(
                            302, Headers.of("Location", "/second"), new byte[0]);
                    case "/second" -> MockUpstream.Response.of(
                            302, Headers.of("Location", "/always-503"), new byte[0]);
                    case "/always-503" -> {
                        targetAttempts.incrementAndGet();
                        yield MockUpstream.Response.text(503, "still transient");
                    }
                    default -> MockUpstream.Response.empty(404);
                });
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder()
                        .redirectPolicy(RedirectPolicy.normal())
                        .retryPolicy(retryPolicy)
                        .build())) {
            var response = client.execute(ClientRequest.get(upstream.baseUri().resolve("/first")));

            assertEquals(503, response.status());
            assertEquals(upstream.baseUri().resolve("/always-503"), response.uri());
            assertEquals(2, response.redirectsFollowed());
            assertEquals(2, targetAttempts.get(), "maximumAttempts(2) permits exactly two target attempts");
            assertEquals(4, upstream.requestCount(), "two redirects do not consume retry-budget attempts");
            upstream.assertHealthy();
        }
    }

    @Test
    void returnsTheOriginalRedirectForMalformedOrUnsupportedLocations() throws Exception {
        try (var upstream = MockUpstream.start(request -> switch (request.target()) {
                    case "/mailto" -> MockUpstream.Response.of(
                            302, Headers.of("Location", "mailto:help@example.test"), new byte[0]);
                    case "/opaque-http" -> MockUpstream.Response.of(
                            302, Headers.of("Location", "http:opaque-target"), new byte[0]);
                    case "/malformed" -> MockUpstream.Response.of(
                            302, Headers.of("Location", "http://[broken"), new byte[0]);
                    default -> MockUpstream.Response.empty(404);
                });
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().redirectPolicy(RedirectPolicy.normal()).build())) {
            assertOriginalRedirect(client, upstream.baseUri().resolve("/mailto"));
            assertOriginalRedirect(client, upstream.baseUri().resolve("/opaque-http"));
            assertOriginalRedirect(client, upstream.baseUri().resolve("/malformed"));
            upstream.assertHealthy();
        }
    }

    @Test
    void enforcesWholeExchangeTimeoutAndCancellationTokenBridge() throws Exception {
        var timeoutEntered = new CountDownLatch(1);
        var cancellationEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> {
            routes.get("/timeout", (request, response) -> waitForRelease(timeoutEntered, release, response));
            routes.get("/cancel", (request, response) -> waitForRelease(cancellationEntered, release, response));
        }).build();
        var timeoutPool = ClientRequestPool.builder().requestTimeout(Duration.ofSeconds(1)).build();
        var cancellationPool = ClientRequestPool.builder().requestTimeout(Duration.ofSeconds(5)).build();
        var token = CancellationToken.create();

        try (var server = Wave.server(app).listen(0).start();
                var timedClient = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(timeoutPool).build());
                var cancelledClient = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(cancellationPool).build())) {
            var base = baseUri(server.port());
            var timed = timedClient.executeAsync(ClientRequest.get(base.resolve("/timeout"))).toCompletableFuture();
            await(timeoutEntered, "timeout request did not reach upstream");
            var timeoutFailure = assertThrows(CompletionException.class, timed::join);
            assertInstanceOf(ClientTimeoutException.class, timeoutFailure.getCause());

            var cancelled = cancelledClient.executeAsync(ClientRequest.builder()
                    .uri(base.resolve("/cancel"))
                    .cancellationToken(token)
                    .build()).toCompletableFuture();
            await(cancellationEntered, "cancellable request did not reach upstream");
            assertTrue(token.cancel("test cancellation"));
            var cancellationFailure = assertThrows(CancellationException.class, cancelled::join);
            var cancellation = assertInstanceOf(ClientCancelledException.class, cancellationFailure.getCause());
            assertEquals("test cancellation", cancellation.getMessage());
        } finally {
            release.countDown();
            timeoutPool.close();
            cancellationPool.close();
        }
    }

    @Test
    void rejectsWhenTheBoundedAdmissionQueueIsFullAndCapsResponseBytes() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> {
            routes.get("/hold", (request, response) -> {
                firstEntered.countDown();
                try {
                    release.await();
                    response.text("first");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    response.text("interrupted");
                }
            });
            routes.get("/large", (request, response) -> response.text("x".repeat(128)));
        }).build();
        var admissionPool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .maximumQueuedRequests(0)
                .requestTimeout(Duration.ofSeconds(5))
                .build();
        var bytePool = ClientRequestPool.builder().maximumResponseBodyBytes(32).build();

        try (var server = Wave.server(app).listen(0).start();
                var admissionClient = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(admissionPool).build());
                var byteClient = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(bytePool).build())) {
            var base = baseUri(server.port());
            var first = admissionClient.executeAsync(ClientRequest.get(base.resolve("/hold"))).toCompletableFuture();
            await(firstEntered, "first request did not acquire the only lease");
            var rejected = admissionClient.executeAsync(ClientRequest.get(base.resolve("/hold"))).toCompletableFuture();

            var rejection = assertThrows(CompletionException.class, rejected::join);
            assertInstanceOf(ClientRequestPoolRejectedException.class, rejection.getCause());
            assertEquals(1, admissionPool.snapshot().leasedRequests());

            release.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).status());

            var bodyFailure = assertThrows(
                    ClientLimitExceededException.class,
                    () -> byteClient.execute(ClientRequest.get(base.resolve("/large"))));
            assertEquals("response body bytes", bodyFailure.limitName());
            assertEquals(32, bodyFailure.limit());
        } finally {
            release.countDown();
            admissionPool.close();
            bytePool.close();
        }
    }

    @Test
    void forwardsAnActualHttpExchangeThroughTheConfiguredProxy() {
        try (var origin = MockUpstream.start(request -> {
                    assertEquals(HttpMethod.GET, request.method());
                    assertEquals("/proxied/item?color=blue", request.target());
                    assertEquals("client-contract", request.headers().first("X-Wave-Test").orElseThrow());
                    return MockUpstream.Response.text(200, "forwarded");
                });
                var proxy = MockUpstream.forwardingProxy();
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder()
                        .proxyPolicy(ProxyPolicy.http(proxy.baseUri()))
                        .build())) {
            var target = origin.baseUri().resolve("/proxied/item?color=blue");
            var response = client.execute(ClientRequest.builder()
                    .uri(target)
                    .header("X-Wave-Test", "client-contract")
                    .build());

            assertEquals(200, response.status());
            assertEquals("forwarded", response.text());
            assertEquals(1, proxy.requestCount());
            assertEquals(target.toString(), proxy.requests().getFirst().target());
            assertEquals(1, origin.requestCount(), "the proxy must forward to a real origin socket");
            proxy.assertHealthy();
            origin.assertHealthy();
        }
    }

    @Test
    void rejectsConnectBeforeOpeningASocketOrLeasingAReusableChannel() {
        var pool = ClientRequestPool.builder().maximumConcurrentRequests(1).build();
        try (var upstream = MockUpstream.start(request -> MockUpstream.Response.text(200, "must not be reached"));
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build())) {
            var exchange = client.executeAsync(ClientRequest.builder()
                    .uri(upstream.baseUri())
                    .method(HttpMethod.CONNECT)
                    .build()).toCompletableFuture();

            var failure = assertThrows(CompletionException.class, exchange::join);
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
            assertEquals(0, upstream.requestCount(), "CONNECT must fail before Bootstrap opens a channel");
            assertEquals(new ClientRequestPool.Snapshot(0, 0, false), pool.snapshot());
            upstream.assertHealthy();
        } finally {
            pool.close();
        }
    }

    @Test
    void returnsAValidCloseDelimitedHttp11ResponseOnlyAfterThePeerCloses() throws Exception {
        var requestReceived = new CountDownLatch(1);
        var bodyFlushed = new CountDownLatch(1);
        var releasePeerClose = new CountDownLatch(1);
        var upstreamFailure = new AtomicReference<Throwable>();
        try (var upstream = new ServerSocket(0);
                var client = io.wavejava.wave.Wave.client()) {
            Thread.ofVirtual().name("wave-client-close-delimited-upstream").start(() -> {
                try (var socket = upstream.accept()) {
                    readHttpHeaders(socket.getInputStream());
                    requestReceived.countDown();
                    var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nclose-delimited")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    bodyFlushed.countDown();
                    releasePeerClose.await();
                } catch (Throwable failure) {
                    upstreamFailure.compareAndSet(null, failure);
                }
            });

            var response = client.executeAsync(ClientRequest.get(baseUri(upstream.getLocalPort()).resolve("/close-delimited")))
                    .toCompletableFuture();

            await(requestReceived, "close-delimited upstream did not receive the request");
            await(bodyFlushed, "close-delimited upstream did not flush the body");
            assertFalse(response.isDone(), "close-delimited response must wait for physical peer close");
            releasePeerClose.countDown();
            var completed = response.get(5, TimeUnit.SECONDS);
            assertEquals(200, completed.status());
            assertEquals("close-delimited", completed.text());
            assertTrue(upstreamFailure.get() == null,
                    () -> "close-delimited upstream failed: " + upstreamFailure.get());
        } finally {
            releasePeerClose.countDown();
        }
    }

    @Test
    void retriesTransportDisconnectForSafeGetButDoesNotReplayDefaultPost() {
        var getAttempts = new AtomicInteger();
        var postAttempts = new AtomicInteger();
        var retryPolicy = RetryPolicy.builder()
                .maximumAttempts(2)
                .retryTransportFailures(true)
                .backoff(BackoffPolicy.none())
                .build();

        try (var upstream = MockUpstream.start(request -> {
                    if (request.target().equals("/retry-after-disconnect")) {
                        if (getAttempts.incrementAndGet() == 1) {
                            return MockUpstream.Response.disconnect();
                        }
                        return MockUpstream.Response.text(200, "recovered");
                    }
                    if (request.target().equals("/unsafe-disconnect")) {
                        postAttempts.incrementAndGet();
                        return MockUpstream.Response.disconnect();
                    }
                    return MockUpstream.Response.empty(404);
                });
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().retryPolicy(retryPolicy).build())) {
            var recovered = client.execute(ClientRequest.get(upstream.baseUri().resolve("/retry-after-disconnect")));
            assertEquals(200, recovered.status());
            assertEquals("recovered", recovered.text());
            assertEquals(2, getAttempts.get(), "a safe GET should get one bounded recovery attempt");

            assertThrows(CompletionException.class, () -> client.execute(ClientRequest.builder()
                    .uri(upstream.baseUri().resolve("/unsafe-disconnect"))
                    .method(HttpMethod.POST)
                    .text("do-not-replay")
                    .build()));
            assertEquals(1, postAttempts.get(), "a default POST must not be replayed after a disconnect");
            upstream.assertHealthy();
        }
    }

    @Test
    void closingAnInFlightClientReleasesItsExternalPoolLease() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .maximumQueuedRequests(0)
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build());

        try (var upstream = MockUpstream.start(request -> {
            entered.countDown();
            release.await();
            return MockUpstream.Response.text(200, "too late");
        })) {
            var inFlight = client.executeAsync(ClientRequest.get(upstream.baseUri().resolve("/in-flight")))
                    .toCompletableFuture();
            await(entered, "in-flight request did not reach mock upstream");
            assertEquals(1, pool.snapshot().leasedRequests());

            client.close();

            var cancellation = assertThrows(CancellationException.class, inFlight::join);
            assertInstanceOf(ClientCancelledException.class, cancellation.getCause());
            awaitCondition(() -> pool.snapshot().leasedRequests() == 0, "client close did not release its pool lease");
            upstream.assertHealthy();
        } finally {
            release.countDown();
            client.close();
            pool.close();
        }
    }

    @Test
    void upstreamDisconnectReleasesThePoolLeaseForTheNextExchange() throws Exception {
        var pool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .maximumQueuedRequests(0)
                .requestTimeout(Duration.ofSeconds(5))
                .build();

        try (var upstream = MockUpstream.start(request -> request.target().equals("/disconnect")
                        ? MockUpstream.Response.disconnect()
                        : MockUpstream.Response.text(200, "next exchange"));
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build())) {
            assertThrows(CompletionException.class,
                    () -> client.execute(ClientRequest.get(upstream.baseUri().resolve("/disconnect"))));
            awaitCondition(() -> pool.snapshot().leasedRequests() == 0,
                    "upstream disconnect leaked the active pool lease");

            var next = client.execute(ClientRequest.get(upstream.baseUri().resolve("/healthy")));
            assertEquals(200, next.status());
            assertEquals("next exchange", next.text());
            assertEquals(0, pool.snapshot().leasedRequests());
            upstream.assertHealthy();
        } finally {
            pool.close();
        }
    }

    @Test
    void cancellationTokenAbortsTheSlowUpstreamExchangeBeforeReleasingItsLease() throws Exception {
        var requestReceived = new CountDownLatch(1);
        var responseStarted = new CountDownLatch(1);
        var peerAborted = new CountDownLatch(1);
        var upstreamFailure = new AtomicReference<Throwable>();
        var token = CancellationToken.create();
        var pool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .maximumQueuedRequests(0)
                .requestTimeout(Duration.ofSeconds(20))
                .build();

        try (var upstream = new ServerSocket(0);
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build())) {
            Thread.ofVirtual().name("wave-client-slow-upstream").start(() -> {
                try (var socket = upstream.accept()) {
                    socket.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
                    var input = socket.getInputStream();
                    readHttpHeaders(input);
                    requestReceived.countDown();
                    var output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\na")
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    responseStarted.countDown();
                    observePeerAbort(input, peerAborted);
                } catch (Throwable failure) {
                    upstreamFailure.compareAndSet(null, failure);
                }
            });

            var inFlight = client.executeAsync(ClientRequest.builder()
                    .uri(baseUri(upstream.getLocalPort()).resolve("/slow"))
                    .cancellationToken(token)
                    .build()).toCompletableFuture();
            await(requestReceived, "client did not reach slow upstream");
            await(responseStarted, "slow upstream did not start the response body");
            assertEquals(1, pool.snapshot().leasedRequests());

            assertTrue(token.cancel("stop slow upstream"));

            var cancellation = assertThrows(CancellationException.class, inFlight::join);
            assertInstanceOf(ClientCancelledException.class, cancellation.getCause());
            assertTrue(peerAborted.await(5, TimeUnit.SECONDS),
                    "cancellation completed without aborting the upstream HTTP/1.1 exchange");
            assertTrue(upstreamFailure.get() == null,
                    () -> "slow upstream failed before observing cancellation: " + upstreamFailure.get());
            awaitCondition(() -> pool.snapshot().leasedRequests() == 0,
                    "cancellation did not release the client pool lease");
        } finally {
            pool.close();
        }
    }

    @Test
    void cancellationDoesNotAdmitAQueuedExchangeUntilThePartialResponseChannelHasClosed() throws Exception {
        var firstRequestReceived = new CountDownLatch(1);
        var firstPartialResponseSent = new CountDownLatch(1);
        var firstPeerClosed = new CountDownLatch(1);
        var secondRequestReceived = new CountDownLatch(1);
        var peerCloseObserved = new AtomicBoolean();
        var secondRequestBeforeFirstClose = new AtomicBoolean();
        var upstreamFailure = new AtomicReference<Throwable>();
        var token = CancellationToken.create();
        var pool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .maximumQueuedRequests(1)
                .requestTimeout(Duration.ofSeconds(20))
                .build();

        try (var upstream = new ServerSocket(0);
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build())) {
            Thread.ofVirtual().name("wave-client-queued-cancellation-upstream").start(() -> {
                try (var first = upstream.accept()) {
                    first.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
                    var firstInput = first.getInputStream();
                    readHttpHeaders(firstInput);
                    firstRequestReceived.countDown();
                    var firstOutput = first.getOutputStream();
                    firstOutput.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\na")
                            .getBytes(StandardCharsets.US_ASCII));
                    firstOutput.flush();
                    firstPartialResponseSent.countDown();
                    Thread.ofVirtual().name("wave-client-first-peer-close-observer").start(() -> {
                        try {
                            observePeerAbort(firstInput);
                            peerCloseObserved.set(true);
                            firstPeerClosed.countDown();
                        } catch (Throwable failure) {
                            upstreamFailure.compareAndSet(null, failure);
                        }
                    });

                    try (var second = upstream.accept()) {
                        second.setSoTimeout((int) Duration.ofSeconds(5).toMillis());
                        readHttpHeaders(second.getInputStream());
                        if (!peerCloseObserved.get()) {
                            secondRequestBeforeFirstClose.set(true);
                        }
                        secondRequestReceived.countDown();
                        var output = second.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nsecond")
                                .getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                    }
                } catch (Throwable failure) {
                    upstreamFailure.compareAndSet(null, failure);
                }
            });

            var first = client.executeAsync(ClientRequest.builder()
                    .uri(baseUri(upstream.getLocalPort()).resolve("/first"))
                    .cancellationToken(token)
                    .build()).toCompletableFuture();
            await(firstRequestReceived, "first exchange did not reach the upstream");
            await(firstPartialResponseSent, "first upstream did not send a partial response");

            var second = client.executeAsync(ClientRequest.get(baseUri(upstream.getLocalPort()).resolve("/second")))
                    .toCompletableFuture();
            assertEquals(new ClientRequestPool.Snapshot(1, 1, false), pool.snapshot());

            assertTrue(token.cancel("cancel first partial response"));
            var cancellation = assertThrows(CancellationException.class, first::join);
            assertInstanceOf(ClientCancelledException.class, cancellation.getCause());

            assertTrue(firstPeerClosed.await(5, TimeUnit.SECONDS),
                    "the first peer never observed FIN/RST from the cancelled transport channel");
            assertTrue(peerCloseObserved.get(), "peer close must be observed before the queued exchange may write");
            assertEquals("second", second.get(5, TimeUnit.SECONDS).text());
            await(secondRequestReceived, "queued second exchange did not reach the upstream");
            assertFalse(secondRequestBeforeFirstClose.get(),
                    "the queued second exchange wrote HTTP request headers before first-channel teardown was observed");
            assertTrue(upstreamFailure.get() == null,
                    () -> "queued-cancellation upstream failed: " + upstreamFailure.get());
        } finally {
            pool.close();
        }
    }

    @Test
    void setupFailureWithoutAnExchangeReturnsTheLeaseImmediately() throws Exception {
        var pool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .maximumQueuedRequests(0)
                .build();

        try (var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build())) {
            var invalid = client.executeAsync(ClientRequest.builder()
                    .uri(URI.create("http://127.0.0.1:1/setup-failure"))
                    .header("Host", "forbidden.example.test")
                    .build()).toCompletableFuture();

            assertThrows(CompletionException.class, invalid::join);
            awaitCondition(() -> pool.snapshot().leasedRequests() == 0,
                    "a pre-exchange setup failure leaked the client pool lease");
        } finally {
            pool.close();
        }
    }

    private static URI baseUri(int port) {
        return URI.create("http://127.0.0.1:" + port);
    }

    private static void assertOriginalRedirect(WaveClient client, URI uri) throws Exception {
        var response = client.executeAsync(ClientRequest.get(uri))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(302, response.status());
        assertEquals(uri, response.uri());
        assertEquals(0, response.redirectsFollowed());
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), message);
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void readHttpHeaders(InputStream input) throws IOException {
        var terminator = new byte[] {'\r', '\n', '\r', '\n'};
        var matched = 0;
        for (var bytes = 0; bytes < 64 * 1024; bytes++) {
            var next = input.read();
            if (next < 0) {
                throw new EOFException("client closed before writing request headers");
            }
            if (next == terminator[matched]) {
                matched++;
                if (matched == terminator.length) {
                    return;
                }
            } else {
                matched = next == terminator[0] ? 1 : 0;
            }
        }
        throw new IOException("client request headers exceeded fixture budget");
    }

    private static void observePeerAbort(InputStream input, CountDownLatch peerAborted) throws IOException {
        observePeerAbort(input);
        peerAborted.countDown();
    }

    private static void observePeerAbort(InputStream input) throws IOException {
        try {
            if (input.read() < 0) {
                return;
            } else {
                throw new IOException("client wrote unexpected bytes after its complete GET request");
            }
        } catch (SocketException reset) {
            // HTTP client cancellation may use either FIN or a TCP reset for an HTTP/1.1 exchange.
            return;
        }
    }

    private static void waitForRelease(
            CountDownLatch entered,
            CountDownLatch release,
            io.wavejava.wave.api.http.Response response) {
        entered.countDown();
        try {
            release.await();
            response.text("released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            response.text("interrupted");
        }
    }
}



