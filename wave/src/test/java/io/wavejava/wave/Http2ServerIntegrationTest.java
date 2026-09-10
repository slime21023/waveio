package io.wavejava.wave;

import io.wavejava.wave.api.server.RunningServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.Http2Config;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.api.server.TlsConfig;
import io.wavejava.wave.netty.TestHttp2Peer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/** Real socket coverage for Wave's TLS/ALPN HTTP/2 server negotiation. */
class Http2ServerIntegrationTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void negotiatesHttp2OverTlsAlpnAndExposesTheLogicalProtocolToTheApplication() throws Exception {
        var app = Wave.app().routes(routes ->
                routes.get("/protocol", (request, response) -> response.text(request.version().wireName())))
                .build();

        try (var server = Wave.server(app)
                .tls(testTls())
                .http2(Http2Config.builder().build())
                .listen(0)
                .start()) {
            var response = httpClient(HttpClient.Version.HTTP_2).send(
                    request(server, "/protocol"), HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertEquals("HTTP/2", response.body());
        }
    }

    @Test
    void prefersHttp2ButRetainsTlsHttp11Fallback() throws Exception {
        var app = Wave.app().routes(routes ->
                routes.get("/protocol", (request, response) -> response.text(request.version().wireName())))
                .build();

        try (var server = Wave.server(app)
                .tls(testTls())
                .http2(Http2Config.builder().mode(Http2Config.Mode.PREFER).build())
                .listen(0)
                .start()) {
            var response = httpClient(HttpClient.Version.HTTP_1_1).send(
                    request(server, "/protocol"), HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpClient.Version.HTTP_1_1, response.version());
            assertEquals(200, response.statusCode());
            assertEquals("HTTP/1.1", response.body());
        }
    }

    @Test
    void multiplexesConcurrentRequestsOnTheWarmTlsAlpnConnection() throws Exception {
        var bothHandlersStarted = new CountDownLatch(2);
        var releaseHandlers = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> {
            routes.get("/warmup", (request, response) -> response.text(request.version().wireName()));
            routes.get("/hold/{id}", (request, response) -> {
                bothHandlersStarted.countDown();
                await(releaseHandlers, "both HTTP/2 streams should reach the application concurrently");
                response.text(request.pathParameter("id").orElseThrow());
            });
        }).build();
        var limits = ServerLimits.defaults().toBuilder().maximumConnections(1).build();
        var http2 = Http2Config.builder().maximumConcurrentStreams(2).build();

        try (var server = Wave.server(app)
                .limits(limits)
                .tls(testTls())
                .http2(http2)
                .listen(0)
                .start()) {
            var client = httpClient(HttpClient.Version.HTTP_2);
            var warmup = client.send(request(server, "/warmup"), HttpResponse.BodyHandlers.ofString());
            assertEquals(HttpClient.Version.HTTP_2, warmup.version());
            assertEquals("HTTP/2", warmup.body());

            var first = client.sendAsync(request(server, "/hold/one"), HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(request(server, "/hold/two"), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(
                        bothHandlersStarted.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                        "both streams should be dispatched before either response is released");
            } finally {
                releaseHandlers.countDown();
            }

            var firstResponse = first.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            var secondResponse = second.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(HttpClient.Version.HTTP_2, firstResponse.version());
            assertEquals(HttpClient.Version.HTTP_2, secondResponse.version());
            assertEquals("one", firstResponse.body());
            assertEquals("two", secondResponse.body());
        } finally {
            releaseHandlers.countDown();
        }
    }

    @Test
    void advertisesTheConcurrentHttp2StreamCapBeforeDispatchingAThirdHandler() throws Exception {
        var admittedHandlers = new AtomicInteger();
        var firstTwoStarted = new CountDownLatch(2);
        var releaseHandlers = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/hold", (request, response) -> {
            admittedHandlers.incrementAndGet();
            firstTwoStarted.countDown();
            await(releaseHandlers, "the admitted streams should be released by the test");
            response.text("released");
        })).build();
        var http2 = Http2Config.builder().maximumConcurrentStreams(2).build();

        try (var server = Wave.server(app).tls(testTls()).http2(http2).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testResourcePath("/tls/localhost-cert.pem"))) {
            var authority = "127.0.0.1:" + server.port();
            var first = peer.open(TestHttp2Peer.requestHeaders(authority, "/hold"), true);
            var second = peer.open(TestHttp2Peer.requestHeaders(authority, "/hold"), true);
            assertTrue(firstTwoStarted.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "two streams should occupy the configured admission budget");

            var overflow = peer.open(TestHttp2Peer.requestHeaders(authority, "/hold"), true);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> overflow.awaitTerminal(REQUEST_TIMEOUT),
                    "the peer must not be allowed to open a stream above the advertised H2 cap");
            assertEquals(2, admittedHandlers.get(),
                    "a stream above the HTTP/2 concurrent-stream cap must not dispatch application code");

            releaseHandlers.countDown();
            assertEquals("released", first.response().get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).text());
            assertEquals("released", second.response().get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).text());
        } finally {
            releaseHandlers.countDown();
        }
    }

    @Test
    void returns503WithoutDispatchingWhenTheSharedHttp2InvocationBudgetIsExhausted() throws Exception {
        var holdStarted = new CountDownLatch(1);
        var releaseHold = new CountDownLatch(1);
        var rejectedHandlerInvocations = new AtomicInteger();
        var app = Wave.app().routes(routes -> {
            routes.get("/hold", (request, response) -> {
                holdStarted.countDown();
                await(releaseHold, "the admitted HTTP/2 invocation should be released by the test");
                response.text("released");
            });
            routes.get("/overflow", (request, response) -> {
                rejectedHandlerInvocations.incrementAndGet();
                response.text("unexpected");
            });
        }).build();
        var limits = ServerLimits.defaults().toBuilder().maximumInFlightRequests(1).build();
        var http2 = Http2Config.builder().maximumConcurrentStreams(2).build();

        try (var server = Wave.server(app).limits(limits).tls(testTls()).http2(http2).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testResourcePath("/tls/localhost-cert.pem"))) {
            var authority = "127.0.0.1:" + server.port();
            var admitted = peer.open(TestHttp2Peer.requestHeaders(authority, "/hold"), true);
            assertTrue(holdStarted.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "the first HTTP/2 stream should consume the only invocation permit");

            var rejected = peer.open(TestHttp2Peer.requestHeaders(authority, "/overflow"), true)
                    .response().get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(503, rejected.status());
            assertEquals(0, rejectedHandlerInvocations.get(),
                    "a stream rejected by the shared invocation budget must not reach application code");

            releaseHold.countDown();
            assertEquals("released", admitted.response().get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).text());
        } finally {
            releaseHold.countDown();
        }
    }

    @Test
    void deliversAnHttp2RequestBodyThroughTheFlowContract() throws Exception {
        var app = Wave.app().routes(routes -> routes.post("/stream", (request, response) -> {
            assertTrue(request.hasStreamingBody());
            response.text(awaitBody(request.streamingBody()));
        })).build();

        try (var server = Wave.server(app)
                .requestStreaming(true)
                .tls(testTls())
                .http2(Http2Config.builder().build())
                .listen(0)
                .start()) {
            var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/stream"))
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString("flow over h2"))
                    .build();
            var response = httpClient(HttpClient.Version.HTTP_2).send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertEquals("flow over h2", response.body());
        }
    }

    @Test
    void writesAFlowResponseAsHttp2DataFrames() throws Exception {
        var app = Wave.app().routes(routes ->
                routes.get("/download", (request, response) -> response.stream(singleBuffer("stream over h2"))))
                .build();

        try (var server = Wave.server(app)
                .tls(testTls())
                .http2(Http2Config.builder().build())
                .listen(0)
                .start()) {
            var response = httpClient(HttpClient.Version.HTTP_2).send(
                    request(server, "/download"), HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertEquals("stream over h2", response.body());
        }
    }

    @Test
    void mapsApplicationFailureToAnHttp2InternalServerError() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/boom", (request, response) -> {
            throw new IllegalStateException("expected test failure");
        })).build();

        try (var server = Wave.server(app)
                .tls(testTls())
                .http2(Http2Config.builder().build())
                .listen(0)
                .start()) {
            var response = httpClient(HttpClient.Version.HTTP_2).send(
                    request(server, "/boom"), HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(500, response.statusCode());
        }
    }

    @Test
    void mapsAnHttp2InvocationDeadlineToGatewayTimeout() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/deadline", (request, response) -> {
            Thread.sleep(Duration.ofSeconds(5));
            response.text("unexpected");
        })).build();
        var timeouts = ServerTimeouts.defaults().toBuilder()
                .requestTimeout(Duration.ofMillis(100))
                .build();

        try (var server = Wave.server(app)
                .tls(testTls())
                .http2(Http2Config.builder().build())
                .timeouts(timeouts)
                .listen(0)
                .start()) {
            var response = httpClient(HttpClient.Version.HTTP_2).send(
                    request(server, "/deadline"), HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(504, response.statusCode());
        }
    }

    @Test
    void shutdownCancelsAnInFlightHttp2StreamInvocation() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var handlerCancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancellationReason = new AtomicReference<String>();
        var app = Wave.app().routes(routes -> routes.get("/hold", (request, response) -> {
            handlerStarted.countDown();
            try {
                release.await();
                response.text("released");
            } catch (InterruptedException expected) {
                cancellationReason.set(request.cancellationToken().orElseThrow().reason().orElseThrow());
                handlerCancelled.countDown();
                response.text("cancelled");
            }
        })).build();
        var server = Wave.server(app)
                .tls(testTls())
                .http2(Http2Config.builder().build())
                .timeouts(ServerTimeouts.defaults().toBuilder().shutdownTimeout(Duration.ofMillis(250)).build())
                .listen(0)
                .start();
        var exchange = httpClient(HttpClient.Version.HTTP_2).sendAsync(
                request(server, "/hold"), HttpResponse.BodyHandlers.ofString());

        try {
            assertTrue(handlerStarted.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "HTTP/2 handler should start before shutdown");
            server.close();
            assertTrue(handlerCancelled.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "shutdown should interrupt an active HTTP/2 invocation");
            assertEquals("server shutdown", cancellationReason.get());
        } finally {
            release.countDown();
            exchange.cancel(true);
            server.close();
        }
    }

    @Test
    void gracefulHttp2ShutdownSendsGoAwayAndLetsAnAdmittedStreamFinish() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/drain", (request, response) -> {
            handlerStarted.countDown();
            await(releaseHandler, "the accepted HTTP/2 stream should be released by the test");
            response.text("drained");
        })).build();
        var server = Wave.server(app)
                .tls(testTls())
                .http2(Http2Config.builder().build())
                .timeouts(ServerTimeouts.defaults().toBuilder().shutdownTimeout(Duration.ofSeconds(5)).build())
                .listen(0)
                .start();
        var closeCompletion = new CompletableFuture<Void>();

        try (var peer = TestHttp2Peer.connect(server.port(), testResourcePath("/tls/localhost-cert.pem"))) {
            var stream = peer.open(TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/drain"), true);
            assertTrue(handlerStarted.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "stream must be admitted before graceful shutdown begins");

            Thread.ofVirtual().start(() -> {
                try {
                    server.close();
                    closeCompletion.complete(null);
                } catch (Throwable failure) {
                    closeCompletion.completeExceptionally(failure);
                }
            });

            var goAway = peer.awaitGoAway(REQUEST_TIMEOUT);
            assertEquals(0L, goAway.errorCode());
            assertTrue(goAway.lastStreamId() > 0, "GOAWAY must include the admitted peer stream");

            releaseHandler.countDown();
            var response = stream.response().get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(200, response.status());
            assertEquals("drained", response.text());
            closeCompletion.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            releaseHandler.countDown();
            server.close();
        }
    }

    @Test
    void aSlowHttp2StreamDoesNotStarveAnIndependentFastStream() throws Exception {
        var slowSubscribed = new CountDownLatch(1);
        var slowCancelled = new CountDownLatch(1);
        var slowPayload = new byte[128 * 1024];
        var slowPublisher = (Flow.Publisher<ByteBuffer>) subscriber -> {
            slowSubscribed.countDown();
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long demand) {
                    if (!emitted && demand > 0) {
                        emitted = true;
                        subscriber.onNext(ByteBuffer.wrap(slowPayload));
                    }
                }

                @Override
                public void cancel() {
                    slowCancelled.countDown();
                }
            });
        };
        var app = Wave.app().routes(routes -> {
            routes.get("/slow", (request, response) -> response.stream(slowPublisher));
            routes.get("/fast", (request, response) -> response.text("fast"));
        }).build();
        var http2 = Http2Config.builder()
                .maximumConcurrentStreams(2)
                .maximumOutboundBytesPerConnection(512 * 1024L)
                .maximumOutboundBytesPerStream(256 * 1024L)
                .build();

        try (var server = Wave.server(app).tls(testTls()).http2(http2).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testResourcePath("/tls/localhost-cert.pem"))) {
            var slow = peer.open(TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/slow"), true);
            slow.pauseReads();
            assertTrue(slowSubscribed.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "the slow stream must be writing before fairness is tested");

            var fast = peer.open(TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/fast"), true);
            var response = fast.response().get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(200, response.status());
            assertEquals("fast", response.text());

            peer.close();
            assertTrue(slowCancelled.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "disconnecting the held slow stream must cancel its Flow source");
        }
    }

    @Test
    void appliesTheStaticHttp2InboundPartitionBeforeDispatchingAnAggregateBody() throws Exception {
        var invoked = new AtomicInteger();
        var app = Wave.app().routes(routes -> routes.post("/bounded", (request, response) -> {
            invoked.incrementAndGet();
            response.text("unexpected");
        })).build();
        var limits = ServerLimits.defaults().toBuilder().maximumRequestBodyBytes(128 * 1024).build();
        var http2 = Http2Config.builder()
                .maximumConcurrentStreams(2)
                .maximumInboundBytesPerConnection(128 * 1024L)
                .initialConnectionWindowBytes(128 * 1024)
                .initialStreamWindowBytes(64 * 1024)
                .build();
        var bodyOverPartition = new byte[64 * 1024 + 1];

        try (var server = Wave.server(app).limits(limits).tls(testTls()).http2(http2).listen(0).start()) {
            var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/bounded"))
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyOverPartition))
                    .build();
            var response = httpClient(HttpClient.Version.HTTP_2).send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(413, response.statusCode());
            assertEquals(0, invoked.get(), "a body beyond its H2 stream partition must not reach the handler");
        }
    }

    private static HttpRequest request(RunningServer server, String path) {
        return HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
    }

    private static TlsConfig testTls() throws Exception {
        return TlsConfig.builder()
                .certificateChain(testResourcePath("/tls/localhost-cert.pem"))
                .privateKey(testResourcePath("/tls/localhost-key.pem"))
                .build();
    }

    private static HttpClient httpClient(HttpClient.Version version) throws GeneralSecurityException {
        var trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        var context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
        return HttpClient.newBuilder()
                .version(version)
                .connectTimeout(REQUEST_TIMEOUT)
                .sslContext(context)
                .build();
    }

    private static Path testResourcePath(String resource) throws Exception {
        var location = Http2ServerIntegrationTest.class.getResource(resource);
        if (location == null) {
            throw new AssertionError("Missing test resource: " + resource);
        }
        return Path.of(location.toURI());
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private static String awaitBody(Flow.Publisher<ByteBuffer> body) {
        var collected = new java.io.ByteArrayOutputStream();
        var completed = new CompletableFuture<Void>();
        body.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer bytes) {
                var copy = new byte[bytes.remaining()];
                bytes.get(copy);
                collected.writeBytes(copy);
            }

            @Override
            public void onError(Throwable failure) {
                completed.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                completed.complete(null);
            }
        });
        try {
            completed.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            throw new AssertionError("HTTP/2 request Flow did not complete", failure);
        }
        return collected.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Flow.Publisher<ByteBuffer> singleBuffer(String value) {
        var bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean terminal;

            @Override
            public void request(long demand) {
                if (terminal) {
                    return;
                }
                terminal = true;
                if (demand <= 0) {
                    subscriber.onError(new IllegalArgumentException("demand must be positive"));
                    return;
                }
                subscriber.onNext(ByteBuffer.wrap(bytes));
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                terminal = true;
            }
        });
    }
}
