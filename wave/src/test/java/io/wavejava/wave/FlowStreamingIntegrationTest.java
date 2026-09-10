package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.server.RequestBodyMode;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Socket-level contracts for 0.4 Flow request and response streaming. */
class FlowStreamingIntegrationTest {
    private static final int TIMEOUT_MILLIS = 5_000;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    @Test
    void streamsResponseWithOneItemDemandAndPreservesPipelinedResponseOrder() throws Exception {
        var publisher = new DemandPublisher(List.of(bytes("one"), bytes("two")));
        var app = Wave.app().routes(routes -> {
            routes.get("/stream", (request, response) -> response.stream(publisher));
            routes.get("/after", (request, response) -> response.text("after"));
        }).build();

        try (var server = Wave.server(app).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/stream", "keep-alive") + request("GET", "/after", "close"));

            var first = readResponse(socket.getInputStream());
            var second = readResponse(socket.getInputStream());

            assertEquals(200, first.status());
            assertEquals("chunked", first.headers().get("transfer-encoding"));
            assertEquals("onetwo", first.body());
            assertEquals(200, second.status());
            assertEquals("after", second.body());
            assertTrue(publisher.subscribed.get(), "transport must subscribe after response headers are written");
            assertTrue(publisher.subscribedOnVirtualThread.get(),
                    "application response publisher subscription must not run on a Netty event loop");
            assertTrue(publisher.requestedOnVirtualThread.get(),
                    "publisher demand and its synchronous onNext callback must not run on a Netty event loop");
            assertTrue(publisher.requestCalls.get() >= 2, "stream output must make progress through Flow demand");
            assertEquals(1, publisher.largestRequested.get(), "transport must never create an unbounded demand window");
            assertEquals(1, publisher.largestConcurrentRequests.get(),
                    "offloaded demand calls must remain serialized for a synchronous publisher");
        }
    }

    @Test
    void headStreamingResponseDoesNotSubscribeOrWritePayload() throws Exception {
        var publisher = new DemandPublisher(List.of(bytes("unreachable")));
        var app = Wave.app().routes(routes -> routes.get("/stream", (request, response) -> response.stream(publisher))).build();

        try (var server = Wave.server(app).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("HEAD", "/stream", "close"));

            var headers = readHeaders(socket.getInputStream());
            assertEquals(200, headers.status());
            assertEquals("chunked", headers.headers().get("transfer-encoding"));
            assertEquals(-1, socket.getInputStream().read(), "HEAD must terminate without entity bytes");
            assertFalse(publisher.subscribed.get(), "HEAD must not consume the response publisher");
        }
    }

    @Test
    void rejectsZeroLengthResponseBuffersAndCancelsThePublisher() throws Exception {
        var publisher = new DemandPublisher(List.of(new byte[0]));
        var app = Wave.app().routes(routes -> routes.get("/zero", (request, response) -> response.stream(publisher))).build();

        try (var server = Wave.server(app).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/zero", "close"));

            assertEquals(200, readHeaders(socket.getInputStream()).status());
            await(publisher.cancelled, "zero-length Flow item must cancel its source");
            assertEquals(-1, socket.getInputStream().read(), "a committed invalid stream closes the connection");
            assertEquals(1, publisher.requestCalls.get(), "invalid zero item cannot trigger an infinite demand loop");
        }
    }

    @Test
    void enforcesTheOutboundByteBudgetBeforeRetainingAnOversizedBuffer() throws Exception {
        var publisher = new DemandPublisher(List.of(bytes("four")));
        var limits = ServerLimits.defaults().toBuilder().maximumOutboundStreamBytesPerConnection(3).build();
        var app = Wave.app().routes(routes -> routes.get("/budget", (request, response) -> response.stream(publisher))).build();

        try (var server = Wave.server(app).limits(limits).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/budget", "close"));

            assertEquals(200, readHeaders(socket.getInputStream()).status());
            await(publisher.cancelled, "over-budget output must cancel its source");
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void clientDisconnectCancelsAnActiveResponsePublisher() throws Exception {
        var publisher = new HoldingPublisher();
        var app = Wave.app().routes(routes -> routes.get("/hold", (request, response) -> response.stream(publisher))).build();

        try (var server = Wave.server(app).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/hold", "keep-alive"));
            assertEquals(200, readHeaders(socket.getInputStream()).status());
            await(publisher.subscribed, "stream publisher should be subscribed");

            socket.close();

            await(publisher.cancelled, "client disconnect must cancel the active response stream");
        }
    }

    @Test
    void gracefulShutdownCancelsAnActiveResponsePublisher() throws Exception {
        var publisher = new HoldingPublisher();
        var app = Wave.app().routes(routes -> routes.get("/hold", (request, response) -> response.stream(publisher))).build();
        var server = Wave.server(app).listen(0).start();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/hold", "keep-alive"));
            assertEquals(200, readHeaders(socket.getInputStream()).status());
            await(publisher.subscribed, "stream publisher should be active before shutdown");

            server.close();

            await(publisher.cancelled, "graceful shutdown must cancel the active response stream");
            assertFalse(server.isRunning());
        } finally {
            server.close();
        }
    }

    @Test
    void responseStreamDeadlineCancelsItsPublisherAndClosesTheSocket() throws Exception {
        var publisher = new HoldingPublisher();
        var timeouts = ServerTimeouts.defaults().toBuilder()
                .requestTimeout(Duration.ofMillis(200))
                .build();
        var app = Wave.app().routes(routes -> routes.get("/deadline", (request, response) -> response.stream(publisher)))
                .build();

        try (var server = Wave.server(app).timeouts(timeouts).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/deadline", "keep-alive"));
            assertEquals(200, readHeaders(socket.getInputStream()).status());
            await(publisher.subscribed, "response stream must be live before its deadline expires");

            await(publisher.cancelled, "response-stream deadline must cancel its publisher");
            assertEquals(-1, socket.getInputStream().read(), "expired response stream must close its connection");
        }
    }

    @Test
    void slowTcpPeerDoesNotTriggerAdditionalResponseDemandBeforeTheFirstWriteCompletes() throws Exception {
        var firstChunk = new byte[16 * 1024 * 1024];
        var publisher = new DemandPublisher(List.of(firstChunk, firstChunk));
        var limits = ServerLimits.defaults().toBuilder()
                .maximumOutboundStreamBytesPerConnection(firstChunk.length)
                .build();
        var app = Wave.app().routes(routes -> routes.get("/slow-peer", (request, response) -> response.stream(publisher)))
                .build();

        try (var server = Wave.server(app).limits(limits).listen(0).start();
                var socket = new Socket()) {
            // Keep the TCP receive window tiny and deliberately never drain the response payload.
            socket.setReceiveBufferSize(1_024);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", server.port()));
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/slow-peer", "keep-alive"));
            assertEquals(200, readHeaders(socket.getInputStream()).status());
            await(publisher.firstRequest, "server must issue its first bounded Flow demand");

            assertFalse(publisher.secondRequest.await(500, TimeUnit.MILLISECONDS),
                    "a TCP-blocked first write must not cause another Flow demand");
            assertEquals(1, publisher.requestCalls.get(),
                    "the peer has not drained the first item, so transport demand remains one item");

            socket.close();
            await(publisher.cancelled, "slow-peer teardown must cancel the live source");
        }
    }

    @Test
    void streamingRequestBodyIsDeliveredInDemandControlledChunks() throws Exception {
        var firstChunk = new CountDownLatch(1);
        var secondChunk = new CountDownLatch(1);
        var allowSecondDemand = new CountDownLatch(1);
        var handlerStarted = new CountDownLatch(1);
        var aggregateReadRejected = new AtomicBoolean();
        var received = new ArrayList<String>();
        var app = Wave.app().routes(routes -> routes.post("/upload", (request, response) -> {
            try {
                request.body();
            } catch (IllegalStateException expected) {
                aggregateReadRejected.set(true);
            }
            var complete = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            request.streamingBody().subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription source) {
                    subscription = source;
                    handlerStarted.countDown();
                    subscription.request(1);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    synchronized (received) {
                        received.add(StandardCharsets.UTF_8.decode(item).toString());
                        if (received.size() == 1) {
                            firstChunk.countDown();
                            await(allowSecondDemand, "test did not permit the second request body chunk");
                        } else {
                            secondChunk.countDown();
                        }
                    }
                    subscription.request(1);
                }

                @Override
                public void onError(Throwable cause) {
                    failure.set(cause);
                    complete.countDown();
                }

                @Override
                public void onComplete() {
                    complete.countDown();
                }
            });
            await(handlerStarted, "request body subscriber should start before the handler waits");
            await(complete, "request body should complete after its terminal chunk");
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            synchronized (received) {
                response.text(String.join("", received));
            }
        })).build();

        try (var server = Wave.server(app)
                .requestBodyMode(RequestBodyMode.STREAMING)
                .listen(0)
                .start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, chunkedRequestHead("/upload", "close"));
            await(handlerStarted, "streaming handler should run from request headers without aggregation");

            write(socket, "3\r\none\r\n");
            await(firstChunk, "first request chunk should arrive after initial Flow demand");
            write(socket, "3\r\ntwo\r\n0\r\n\r\n");
            assertFalse(secondChunk.await(250, TimeUnit.MILLISECONDS),
                    "the second chunk must remain in the socket until the subscriber demands it");

            allowSecondDemand.countDown();
            await(secondChunk, "second chunk should arrive after the next Flow demand");
            var response = readResponse(socket.getInputStream());

            assertEquals(200, response.status());
            assertEquals("onetwo", response.body());
            assertTrue(aggregateReadRejected.get(), "streaming request and aggregate Body must be mutually exclusive");
        } finally {
            allowSecondDemand.countDown();
        }
    }

    @Test
    void coalescedChunkedRequestBodyStillDeliversOnlyAfterEachFlowDemand() throws Exception {
        var firstChunk = new CountDownLatch(1);
        var secondChunk = new CountDownLatch(1);
        var allowSecondDemand = new CountDownLatch(1);
        var subscribed = new CountDownLatch(1);
        var received = new ArrayList<String>();
        var app = Wave.app().routes(routes -> routes.post("/coalesced", (request, response) -> {
            var complete = new CountDownLatch(1);
            request.streamingBody().subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription source) {
                    subscription = source;
                    subscribed.countDown();
                    source.request(1);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    synchronized (received) {
                        received.add(StandardCharsets.UTF_8.decode(item).toString());
                        if (received.size() == 1) {
                            firstChunk.countDown();
                            await(allowSecondDemand, "test did not permit second demand");
                        } else {
                            secondChunk.countDown();
                        }
                    }
                    subscription.request(1);
                }

                @Override
                public void onError(Throwable failure) {
                    throw new AssertionError(failure);
                }

                @Override
                public void onComplete() {
                    complete.countDown();
                }
            });
            await(complete, "coalesced request body should complete after final demand");
            synchronized (received) {
                response.text(String.join("", received));
            }
        })).build();

        try (var server = Wave.server(app).requestStreaming(true).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, chunkedRequestHead("/coalesced", "close"));
            await(subscribed, "handler should subscribe before body bytes arrive");

            // All three wire chunks share one socket write. The decoder must retain the later
            // chunks itself and expose no more than one copied item to InboundFlowBridge.
            write(socket, "3\r\none\r\n3\r\ntwo\r\n0\r\n\r\n");
            await(firstChunk, "first Flow item should be delivered");
            assertFalse(secondChunk.await(250, TimeUnit.MILLISECONDS),
                    "a coalesced second HTTP chunk must wait for the second Flow demand");

            allowSecondDemand.countDown();
            await(secondChunk, "second Flow item should arrive after demand resumes");
            var response = readResponse(socket.getInputStream());
            assertEquals(200, response.status());
            assertEquals("onetwo", response.body());
        } finally {
            allowSecondDemand.countDown();
        }
    }

    @Test
    void terminalEmptyBodiesThatAreNeverSubscribedDoNotAccumulateConnectionTracking() throws Exception {
        var app = Wave.app().routes(routes -> routes.post("/empty", (request, response) -> response.text("ok")))
                .build();

        try (var server = Wave.server(app).requestStreaming(true).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            for (var index = 0; index < 24; index++) {
                write(socket, emptyPost("/empty", "keep-alive"));
                var response = readResponse(socket.getInputStream());
                assertEquals(200, response.status());
                assertEquals("ok", response.body());
                assertEquals("keep-alive", response.headers().get("connection"));
            }

            write(socket, emptyPost("/empty", "close"));
            assertEquals(200, readResponse(socket.getInputStream()).status());
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void oversizedContentLengthWithExpectReturnsOnly413BeforeHandlerOrBody() throws Exception {
        var handlerCalled = new AtomicBoolean();
        var limits = ServerLimits.defaults().toBuilder().maximumRequestBodyBytes(3).build();
        var app = Wave.app().routes(routes -> routes.post("/limited", (request, response) -> {
            handlerCalled.set(true);
            response.text("unreachable");
        })).build();

        try (var server = Wave.server(app).limits(limits).requestStreaming(true).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, "POST /limited HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Length: 4\r\n"
                    + "Expect: 100-continue\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");

            var response = readResponse(socket.getInputStream());
            assertEquals(413, response.status(), "the first and only response must reject the header");
            assertFalse(handlerCalled.get(), "known over-limit Content-Length must not reach application code");
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void overLimitBodyBehindAnActiveResponseAbortsWithoutAppendingRaw413() throws Exception {
        var upstream = new HoldingPublisher();
        var bodySubscribed = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> {
            routes.get("/hold", (request, response) -> response.stream(upstream));
            routes.post("/limited", (request, response) -> {
                request.streamingBody().subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription source) {
                        bodySubscribed.countDown();
                        source.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(ByteBuffer item) {
                        // Flow demand remains open so the next coalesced chunk reaches the budget check.
                    }

                    @Override
                    public void onError(Throwable failure) {
                        // The transport closes this request after the byte-budget breach.
                    }

                    @Override
                    public void onComplete() {
                        throw new AssertionError("over-limit input must not complete");
                    }
                });
                response.text("later");
            });
        }).build();
        var limits = ServerLimits.defaults().toBuilder().maximumRequestBodyBytes(3).build();

        try (var server = Wave.server(app).limits(limits).requestStreaming(true).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/hold", "keep-alive"));
            await(upstream.subscribed, "first response stream should become active");
            write(socket, chunkedRequestHead("/limited", "keep-alive"));
            await(bodySubscribed, "streaming POST handler should subscribe before body bytes arrive");

            var head = readHeaders(socket.getInputStream());
            assertEquals(200, head.status());
            assertEquals("chunked", head.headers().get("transfer-encoding"));

            write(socket, "3\r\none\r\n1\r\nx\r\n0\r\n\r\n");
            assertEquals(-1, socket.getInputStream().read(),
                    "a budget failure must abort an active HTTP/1.1 stream, never append a raw 413");
            await(upstream.cancelled, "connection abort should cancel the predecessor response publisher");
        }
    }

    @Test
    void overLimitBodyBehindAnEarlierUnpreparedInvocationAbortsWithoutRaw413() throws Exception {
        var firstHandlerStarted = new CountDownLatch(1);
        var releaseFirstHandler = new CountDownLatch(1);
        var bodySubscribed = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> {
            routes.get("/block", (request, response) -> {
                firstHandlerStarted.countDown();
                try {
                    releaseFirstHandler.await();
                } catch (InterruptedException expected) {
                    // The over-limit successor aborts the connection and cancels this handler.
                    Thread.currentThread().interrupt();
                }
                response.text("first");
            });
            routes.post("/limited", (request, response) -> {
                request.streamingBody().subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription source) {
                        bodySubscribed.countDown();
                        source.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(ByteBuffer item) {
                        // Keep demand open until the cumulative byte budget fails.
                    }

                    @Override
                    public void onError(Throwable failure) {
                        // Connection abort is the expected result when an older response exists.
                    }

                    @Override
                    public void onComplete() {
                        throw new AssertionError("over-limit input must not complete");
                    }
                });
                response.text("later");
            });
        }).build();
        var limits = ServerLimits.defaults().toBuilder().maximumRequestBodyBytes(3).build();

        try (var server = Wave.server(app).limits(limits).requestStreaming(true).listen(0).start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, request("GET", "/block", "keep-alive"));
            await(firstHandlerStarted, "first GET should remain an earlier outstanding invocation");
            write(socket, chunkedRequestHead("/limited", "keep-alive"));
            await(bodySubscribed, "second streaming request should subscribe before body bytes arrive");

            write(socket, "3\r\none\r\n1\r\nx\r\n0\r\n\r\n");
            assertEquals(-1, socket.getInputStream().read(),
                    "a raw 413 may not overtake an earlier request whose response is not prepared");
        } finally {
            releaseFirstHandler.countDown();
        }
    }

    @Test
    void clientDisconnectCancelsAStreamingRequestPublisher() throws Exception {
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var cancellation = new AtomicReference<Throwable>();
        var handlerRelease = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.post("/disconnect", (request, response) -> {
            request.streamingBody().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription source) {
                    source.request(1);
                    subscribed.countDown();
                }

                @Override
                public void onNext(ByteBuffer item) {
                    throw new AssertionError("no request body bytes were sent");
                }

                @Override
                public void onError(Throwable failure) {
                    cancellation.set(failure);
                    cancelled.countDown();
                }

                @Override
                public void onComplete() {
                    throw new AssertionError("disconnect must not complete the request publisher");
                }
            });
            try {
                handlerRelease.await();
            } catch (InterruptedException expected) {
                // Transport cancellation interrupts the handler as well as the publisher.
            }
            response.text("unreachable");
        })).build();

        try (var server = Wave.server(app)
                .requestStreaming(true)
                .listen(0)
                .start();
                var socket = new Socket("127.0.0.1", server.port())) {
            write(socket, chunkedRequestHead("/disconnect", "keep-alive"));
            await(subscribed, "request body publisher should subscribe from the handler");

            socket.close();

            await(cancelled, "disconnect must signal the request body publisher");
            assertTrue(cancellation.get() instanceof CancellationException);
        } finally {
            handlerRelease.countDown();
        }
    }

    @Test
    void gracefulShutdownCancelsAnActiveStreamingRequestPublisherOffEventLoop() throws Exception {
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var callbackRanOnVirtualThread = new AtomicBoolean();
        var handlerRelease = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.post("/shutdown-request", (request, response) -> {
            request.streamingBody().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription source) {
                    source.request(1);
                    subscribed.countDown();
                }

                @Override
                public void onNext(ByteBuffer item) {
                    throw new AssertionError("no request body bytes were sent");
                }

                @Override
                public void onError(Throwable failure) {
                    callbackRanOnVirtualThread.set(Thread.currentThread().isVirtual());
                    cancelled.countDown();
                }

                @Override
                public void onComplete() {
                    throw new AssertionError("shutdown must cancel an unfinished request body");
                }
            });
            try {
                handlerRelease.await();
            } catch (InterruptedException expected) {
                // Server cancellation is allowed to interrupt the handler after publisher teardown.
            }
            response.text("unreachable");
        })).build();
        var server = Wave.server(app).requestStreaming(true).listen(0).start();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            write(socket, chunkedRequestHead("/shutdown-request", "keep-alive"));
            await(subscribed, "request publisher should be active before shutdown");

            server.close();

            await(cancelled, "shutdown must deliver request publisher cancellation");
            assertTrue(callbackRanOnVirtualThread.get(), "request onError must never run on Netty's event loop");
            assertFalse(server.isRunning());
        } finally {
            handlerRelease.countDown();
            server.close();
        }
    }

    @Test
    void unconsumedStreamingRequestBodyMakesTheCommittedResponseConnectionFinal() throws Exception {
        var app = Wave.app().routes(routes -> routes.post("/ignore", (request, response) -> response.text("ignored")))
                .build();

        try (var server = Wave.server(app)
                .requestBodyMode(RequestBodyMode.STREAMING)
                .listen(0)
                .start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, chunkedRequestHead("/ignore", "keep-alive"));

            var response = readResponse(socket.getInputStream());

            assertEquals(200, response.status());
            assertEquals("ignored", response.body());
            assertEquals("close", response.headers().get("connection"));
            assertEquals(-1, socket.getInputStream().read(),
                    "a handler that does not consume the body cannot leave an idle half-read connection");
        }
    }

    @Test
    void streamingRequestBodyLimitReturns413WithoutAnAggregator() throws Exception {
        var handlerCalled = new AtomicBoolean();
        var app = Wave.app().routes(routes -> routes.post("/limited", (request, response) -> {
            handlerCalled.set(true);
            response.text("unreachable");
        })).build();
        var limits = ServerLimits.defaults().toBuilder().maximumRequestBodyBytes(3).build();

        try (var server = Wave.server(app)
                .limits(limits)
                .requestStreaming(true)
                .listen(0)
                .start();
                var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            write(socket, "POST /limited HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Length: 4\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");
            assertEquals(413, readResponse(socket.getInputStream()).status());
            assertFalse(handlerCalled.get(), "known oversized Content-Length must fail at the request head");
        }
    }

    private static void write(Socket socket, String wire) throws IOException {
        socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String request(String method, String path, String connection) {
        return method + " " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: " + connection + "\r\n"
                + "\r\n";
    }

    private static String chunkedRequestHead(String path, String connection) {
        return "POST " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: " + connection + "\r\n"
                + "\r\n";
    }

    private static String emptyPost(String path, String connection) {
        return "POST " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: " + connection + "\r\n"
                + "\r\n";
    }

    private static RawResponse readResponse(InputStream input) throws IOException {
        var head = readHeaders(input);
        var transferEncoding = head.headers().get("transfer-encoding");
        if ("chunked".equalsIgnoreCase(transferEncoding)) {
            return new RawResponse(head.status(), head.headers(), readChunkedBody(input));
        }
        var contentLength = Integer.parseInt(head.headers().getOrDefault("content-length", "0"));
        var payload = input.readNBytes(contentLength);
        if (payload.length != contentLength) {
            throw new EOFException("connection closed before the complete fixed-length response body");
        }
        return new RawResponse(head.status(), head.headers(), new String(payload, StandardCharsets.UTF_8));
    }

    private static RawHeaders readHeaders(InputStream input) throws IOException {
        var block = readHeaderBlock(input);
        var lines = block.split("\\r\\n");
        var statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2) {
            throw new IOException("invalid HTTP status line: " + lines[0]);
        }
        var headers = new LinkedHashMap<String, String>();
        for (int index = 1; index < lines.length; index++) {
            var separator = lines[index].indexOf(':');
            if (separator <= 0) {
                throw new IOException("invalid response header: " + lines[index]);
            }
            headers.put(
                    lines[index].substring(0, separator).toLowerCase(Locale.ROOT),
                    lines[index].substring(separator + 1).trim());
        }
        return new RawHeaders(Integer.parseInt(statusParts[1]), Map.copyOf(headers));
    }

    private static String readChunkedBody(InputStream input) throws IOException {
        var body = new ByteArrayOutputStream();
        while (true) {
            var sizeLine = readAsciiLine(input);
            var separator = sizeLine.indexOf(';');
            var encodedSize = separator < 0 ? sizeLine : sizeLine.substring(0, separator);
            var size = Integer.parseInt(encodedSize.trim(), 16);
            if (size == 0) {
                readAsciiLine(input); // Empty terminal trailer line.
                return body.toString(StandardCharsets.UTF_8);
            }
            var chunk = input.readNBytes(size);
            if (chunk.length != size) {
                throw new EOFException("connection closed inside a chunked response item");
            }
            body.write(chunk);
            if (!readAsciiLine(input).isEmpty()) {
                throw new IOException("chunk payload was not followed by CRLF");
            }
        }
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        while (true) {
            var next = input.read();
            if (next < 0) {
                throw new EOFException("connection closed before a complete HTTP line");
            }
            if (next == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("HTTP line was not CRLF terminated");
                }
                return bytes.toString(StandardCharsets.US_ASCII);
            }
            bytes.write(next);
        }
    }

    private static String readHeaderBlock(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var delimiterState = 0;
        for (int count = 0; count < MAX_HEADER_BYTES; count++) {
            var next = input.read();
            if (next < 0) {
                throw new EOFException("connection closed before response headers completed");
            }
            bytes.write(next);
            delimiterState = switch (delimiterState) {
                case 0 -> next == '\r' ? 1 : 0;
                case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                case 2 -> next == '\r' ? 3 : 0;
                case 3 -> next == '\n' ? 4 : next == '\r' ? 1 : 0;
                default -> throw new AssertionError("header delimiter state out of range");
            };
            if (delimiterState == 4) {
                var wire = bytes.toString(StandardCharsets.US_ASCII);
                return wire.substring(0, wire.length() - 4);
            }
        }
        throw new IOException("response headers exceeded " + MAX_HEADER_BYTES + " bytes");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private record RawHeaders(int status, Map<String, String> headers) {
    }

    private record RawResponse(int status, Map<String, String> headers, String body) {
    }

    /** A deliberately synchronous source: it reveals exactly the demand issued by the bridge. */
    private static final class DemandPublisher implements Flow.Publisher<ByteBuffer> {
        private final List<byte[]> chunks;
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private final AtomicBoolean subscribedOnVirtualThread = new AtomicBoolean();
        private final AtomicBoolean requestedOnVirtualThread = new AtomicBoolean();
        private final AtomicInteger requestCalls = new AtomicInteger();
        private final AtomicInteger largestRequested = new AtomicInteger();
        private final AtomicInteger concurrentRequests = new AtomicInteger();
        private final AtomicInteger largestConcurrentRequests = new AtomicInteger();
        private final CountDownLatch firstRequest = new CountDownLatch(1);
        private final CountDownLatch secondRequest = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        private DemandPublisher(List<byte[]> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> target) {
            if (!subscribed.compareAndSet(false, true)) {
                target.onSubscribe(RejectedSubscription.INSTANCE);
                target.onError(new IllegalStateException("publisher supports one subscriber"));
                return;
            }
            subscribedOnVirtualThread.set(Thread.currentThread().isVirtual());
            target.onSubscribe(new Flow.Subscription() {
                private int next;
                private boolean terminated;

                @Override
                public void request(long demand) {
                    var active = concurrentRequests.incrementAndGet();
                    largestConcurrentRequests.accumulateAndGet(active, Math::max);
                    try {
                        synchronized (this) {
                            if (terminated) {
                                return;
                            }
                            if (demand <= 0) {
                                terminated = true;
                                target.onError(new IllegalArgumentException("demand must be positive"));
                                return;
                            }
                            requestedOnVirtualThread.set(Thread.currentThread().isVirtual());
                            var requestCount = requestCalls.incrementAndGet();
                            if (requestCount == 1) {
                                firstRequest.countDown();
                            } else if (requestCount == 2) {
                                secondRequest.countDown();
                            }
                            largestRequested.accumulateAndGet(Math.toIntExact(demand), Math::max);
                            var remaining = demand;
                            while (!terminated && remaining-- > 0 && next < chunks.size()) {
                                target.onNext(ByteBuffer.wrap(chunks.get(next++)).asReadOnlyBuffer());
                            }
                            if (!terminated && next == chunks.size()) {
                                terminated = true;
                                target.onComplete();
                            }
                        }
                    } finally {
                        concurrentRequests.decrementAndGet();
                    }
                }

                @Override
                public synchronized void cancel() {
                    if (!terminated) {
                        terminated = true;
                        cancelled.countDown();
                    }
                }
            });
        }
    }

    /** Source which remains live after its first Flow demand, for disconnect cancellation tests. */
    private static final class HoldingPublisher implements Flow.Publisher<ByteBuffer> {
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> target) {
            target.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean done = new AtomicBoolean();

                @Override
                public void request(long demand) {
                    subscribed.countDown();
                }

                @Override
                public void cancel() {
                    if (done.compareAndSet(false, true)) {
                        cancelled.countDown();
                    }
                }
            });
        }
    }

    private enum RejectedSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long demand) {
            // The rejected subscriber receives onError immediately.
        }

        @Override
        public void cancel() {
            // Nothing to cancel.
        }
    }
}
