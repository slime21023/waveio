package io.wavejava.wave.api.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.server.Http2Config;
import io.wavejava.wave.api.server.TlsConfig;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real TLS/ALPN contract coverage for WaveClient's owned HTTP/2 transport. */
class Http2WaveClientIntegrationTest {
    @Test
    void executesAByteBodyExchangeThroughTheOwnedHttp2Transport() throws Exception {
        var app = Wave.app().routes(routes ->
                routes.get("/protocol", (request, response) -> response.text(request.version().wireName())))
                .build();
        var http2 = Http2Config.builder().maximumConcurrentStreams(2).build();

        try (var server = Wave.server(app).tls(testTls()).http2(http2).listen(0).start();
                var client = WaveClient.builder().http2(http2).tls(testClientTls()).build()) {
            var response = client.execute(ClientRequest.get(
                    URI.create("https://127.0.0.1:" + server.port() + "/protocol")));

            assertEquals(200, response.status());
            assertEquals("HTTP/2", response.text());
        }
    }

    @Test
    void reusesOneAlpnConnectionForConcurrentHttp2Streams() throws Exception {
        var bothHandlersStarted = new CountDownLatch(2);
        var releaseHandlers = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> {
            routes.get("/warmup", (request, response) -> response.text(request.version().wireName()));
            routes.get("/hold/{id}", (request, response) -> {
                bothHandlersStarted.countDown();
                try {
                    if (!releaseHandlers.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release HTTP/2 handlers");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("HTTP/2 handler interrupted", interrupted);
                }
                response.text(request.pathParameter("id").orElseThrow());
            });
        }).build();
        var http2 = Http2Config.builder().maximumConcurrentStreams(2).build();
        var limits = io.wavejava.wave.api.server.ServerLimits.defaults().toBuilder()
                .maximumConnections(1)
                .build();

        try (var server = Wave.server(app).limits(limits).tls(testTls()).http2(http2).listen(0).start();
                var client = WaveClient.builder().http2(http2).tls(testClientTls()).build()) {
            var base = "https://127.0.0.1:" + server.port();
            assertEquals("HTTP/2", client.execute(ClientRequest.get(URI.create(base + "/warmup"))).text());

            var first = client.executeAsync(ClientRequest.get(URI.create(base + "/hold/one")));
            var second = client.executeAsync(ClientRequest.get(URI.create(base + "/hold/two")));
            try {
                org.junit.jupiter.api.Assertions.assertTrue(
                        bothHandlersStarted.await(5, TimeUnit.SECONDS),
                        "both client streams should share the established HTTP/2 connection");
            } finally {
                releaseHandlers.countDown();
            }

            assertEquals("one", first.toCompletableFuture().get(5, TimeUnit.SECONDS).text());
            assertEquals("two", second.toCompletableFuture().get(5, TimeUnit.SECONDS).text());
        } finally {
            releaseHandlers.countDown();
        }
    }

    @Test
    void preferModeFallsBackToTheOwnedHttp11TransportAfterAlpnSelectsHttp11() throws Exception {
        var app = Wave.app().routes(routes ->
                routes.get("/protocol", (request, response) -> response.text(request.version().wireName())))
                .build();

        try (var server = Wave.server(app).tls(testTls()).listen(0).start();
                var client = WaveClient.builder()
                        .http2(Http2Config.builder().mode(Http2Config.Mode.PREFER).build())
                        .tls(testClientTls())
                        .build()) {
            var response = client.execute(ClientRequest.get(
                    URI.create("https://127.0.0.1:" + server.port() + "/protocol")));

            assertEquals(200, response.status());
            assertEquals("HTTP/1.1", response.text());
        }
    }

    @Test
    void establishesTlsAlpnHttp2ThroughAnHttpConnectProxyTunnel() throws Exception {
        var app = Wave.app().routes(routes ->
                routes.get("/protocol", (request, response) -> response.text(request.version().wireName())))
                .build();
        var http2 = Http2Config.builder().build();

        try (var server = Wave.server(app).tls(testTls()).http2(http2).listen(0).start();
                var proxy = ConnectProxy.start();
                var client = WaveClient.builder()
                        .http2(http2)
                        .tls(testClientTls())
                        .proxyPolicy(ProxyPolicy.http(proxy.baseUri()))
                        .build()) {
            var response = client.execute(ClientRequest.get(
                    URI.create("https://127.0.0.1:" + server.port() + "/protocol")));

            assertEquals(200, response.status());
            assertEquals("HTTP/2", response.text());
            assertTrue(proxy.awaitConnect(5, TimeUnit.SECONDS), "client should send CONNECT before TLS/ALPN");
            assertEquals("CONNECT 127.0.0.1:" + server.port() + " HTTP/1.1", proxy.connectStartLine());
            proxy.assertHealthy();
        }
    }

    @Test
    void enforcesStaticHttp2ConnectionBudgetPartitionsForByteClientBodies() throws Exception {
        var byteOverPartition = 64 * 1024 + 1;
        var app = Wave.app().routes(routes -> {
            routes.get("/large-response", (request, response) ->
                    response.bytes(new byte[byteOverPartition], MediaType.APPLICATION_OCTET_STREAM));
            routes.post("/large-request", (request, response) -> response.text("unexpected"));
        }).build();
        var http2 = Http2Config.builder()
                .maximumConcurrentStreams(2)
                .maximumInboundBytesPerConnection(128 * 1024)
                .initialConnectionWindowBytes(128 * 1024)
                .initialStreamWindowBytes(64 * 1024)
                .maximumOutboundBytesPerConnection(128 * 1024)
                .maximumOutboundBytesPerStream(128 * 1024)
                .build();
        var pool = ClientRequestPool.builder()
                .maximumRequestBodyBytes(128 * 1024)
                .maximumResponseBodyBytes(128 * 1024)
                .build();

        try (var server = Wave.server(app).tls(testTls()).http2(http2).listen(0).start();
                var client = WaveClient.builder().requestPool(pool).http2(http2).tls(testClientTls()).build()) {
            var base = "https://127.0.0.1:" + server.port();
            var inboundFailure = assertThrows(ExecutionException.class, () -> client.executeAsync(
                    ClientRequest.get(URI.create(base + "/large-response"))).toCompletableFuture().get(5, TimeUnit.SECONDS));
            var inboundLimit = assertInstanceOf(ClientLimitExceededException.class, inboundFailure.getCause());
            assertEquals("HTTP/2 inbound stream bytes", inboundLimit.limitName());
            assertEquals(64 * 1024, inboundLimit.limit());

            var outboundFailure = assertThrows(ExecutionException.class, () -> client.executeAsync(ClientRequest.builder()
                    .uri(URI.create(base + "/large-request"))
                    .method(HttpMethod.POST)
                    .body(new byte[byteOverPartition])
                    .build()).toCompletableFuture().get(5, TimeUnit.SECONDS));
            var outboundLimit = assertInstanceOf(ClientLimitExceededException.class, outboundFailure.getCause());
            assertEquals("HTTP/2 outbound stream bytes", outboundLimit.limitName());
            assertEquals(64 * 1024, outboundLimit.limit());
        }
    }

    @Test
    void cancellingAnHttp2ClientExchangeResetsTheStreamAndCancelsTheServerInvocation() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var handlerCancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancellationReason = new AtomicReference<String>();
        var app = Wave.app().routes(routes -> {
            routes.get("/hold", (request, response) -> {
                handlerStarted.countDown();
                try {
                    release.await();
                    response.text("released");
                } catch (InterruptedException expected) {
                    cancellationReason.set(request.cancellationToken().orElseThrow().reason().orElseThrow());
                    handlerCancelled.countDown();
                    response.text("cancelled");
                }
            });
            routes.get("/ok", (request, response) -> response.text(request.version().wireName()));
        }).build();
        var http2 = Http2Config.builder().build();
        var limits = io.wavejava.wave.api.server.ServerLimits.defaults().toBuilder().maximumConnections(1).build();

        try (var server = Wave.server(app).limits(limits).tls(testTls()).http2(http2).listen(0).start();
                var client = WaveClient.builder().http2(http2).tls(testClientTls()).build()) {
            var cancellation = new io.wavejava.wave.api.http.CancellationToken();
            var future = client.executeAsync(ClientRequest.get(
                    URI.create("https://127.0.0.1:" + server.port() + "/hold"))
                    .toBuilder()
                    .cancellationToken(cancellation)
                    .build()).toCompletableFuture();
            try {
                assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "server handler should start before cancellation");
                assertTrue(cancellation.cancel("test cancellation"), "request cancellation token should accept cancellation");
                assertTrue(handlerCancelled.await(5, TimeUnit.SECONDS),
                        "HTTP/2 stream cancellation should interrupt the server invocation");
                assertTrue(cancellationReason.get().startsWith("HTTP/2 stream reset")
                                || cancellationReason.get().equals("client disconnected"),
                        () -> "server should receive an HTTP/2 reset or peer close: " + cancellationReason.get());
                assertEquals("HTTP/2", client.execute(ClientRequest.get(
                        URI.create("https://127.0.0.1:" + server.port() + "/ok"))).text());
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void timingOutAnHttp2ClientExchangeResetsTheStreamAndKeepsTheParentReusable() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var handlerCancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> {
            routes.get("/hold", (request, response) -> {
                handlerStarted.countDown();
                try {
                    release.await();
                    response.text("released");
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                    handlerCancelled.countDown();
                }
            });
            routes.get("/ok", (request, response) -> response.text(request.version().wireName()));
        }).build();
        var http2 = Http2Config.builder().build();
        var limits = io.wavejava.wave.api.server.ServerLimits.defaults().toBuilder().maximumConnections(1).build();
        var pool = ClientRequestPool.builder()
                .maximumConcurrentRequests(1)
                .requestTimeout(java.time.Duration.ofMillis(500))
                .build();

        try (var server = Wave.server(app).limits(limits).tls(testTls()).http2(http2).listen(0).start();
                var client = WaveClient.builder().requestPool(pool).http2(http2).tls(testClientTls()).build()) {
            var base = "https://127.0.0.1:" + server.port();
            var timedOut = client.executeAsync(ClientRequest.get(URI.create(base + "/hold"))).toCompletableFuture();
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "server handler should start before client deadline");

            var timeoutFailure = assertThrows(ExecutionException.class,
                    () -> timedOut.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ClientTimeoutException.class, timeoutFailure.getCause());
            assertTrue(handlerCancelled.await(5, TimeUnit.SECONDS),
                    "the request deadline must reset the HTTP/2 stream at the server");
            assertEquals("HTTP/2", client.execute(ClientRequest.get(URI.create(base + "/ok"))).text(),
                    "a timed-out stream must not poison the reusable HTTP/2 parent");
        } finally {
            release.countDown();
        }
    }

    @Test
    void keepsAFastHttp2ExchangeLiveWhileAnotherPeerResponseStreamIsHeld() throws Exception {
        var slowSubscribed = new CountDownLatch(1);
        var slowCancelled = new CountDownLatch(1);
        var slowPublisher = (Flow.Publisher<ByteBuffer>) subscriber -> {
            slowSubscribed.countDown();
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean cancelled = new AtomicBoolean();

                @Override
                public void request(long demand) {
                    // Deliberately retain demand without producing a body chunk: this models a
                    // peer whose response remains open while an independent stream must progress.
                }

                @Override
                public void cancel() {
                    if (cancelled.compareAndSet(false, true)) {
                        slowCancelled.countDown();
                    }
                }
            });
        };
        var app = Wave.app().routes(routes -> {
            routes.get("/slow", (request, response) -> response.stream(slowPublisher));
            routes.get("/fast", (request, response) -> response.text("fast"));
            routes.get("/after", (request, response) -> response.text(request.version().wireName()));
        }).build();
        var http2 = Http2Config.builder().maximumConcurrentStreams(2).build();
        var limits = io.wavejava.wave.api.server.ServerLimits.defaults().toBuilder()
                .maximumConnections(1)
                .build();

        try (var server = Wave.server(app).limits(limits).tls(testTls()).http2(http2).listen(0).start();
                var client = WaveClient.builder().http2(http2).tls(testClientTls()).build()) {
            var base = "https://127.0.0.1:" + server.port();
            var cancellation = new io.wavejava.wave.api.http.CancellationToken();
            var slow = client.executeAsync(ClientRequest.get(URI.create(base + "/slow"))
                    .toBuilder()
                    .cancellationToken(cancellation)
                    .build()).toCompletableFuture();

            assertTrue(slowSubscribed.await(5, TimeUnit.SECONDS),
                    "the held response must occupy an HTTP/2 stream before fairness is tested");
            assertEquals("fast", client.execute(ClientRequest.get(URI.create(base + "/fast"))).text(),
                    "one held peer response must not block an independent stream on the same parent");

            assertTrue(cancellation.cancel("finish held HTTP/2 response"));
            var cancellationFailure = assertThrows(CancellationException.class, slow::join);
            assertInstanceOf(ClientCancelledException.class, cancellationFailure.getCause());
            assertTrue(slowCancelled.await(5, TimeUnit.SECONDS),
                    "client cancellation must reset the held HTTP/2 response stream at the peer");
            assertEquals("HTTP/2", client.execute(ClientRequest.get(URI.create(base + "/after"))).text(),
                    "the cancelled stream must not poison the reusable HTTP/2 parent");
        }
    }

    private static TlsConfig testTls() throws Exception {
        return TlsConfig.builder()
                .certificateChain(testResourcePath("/tls/localhost-cert.pem"))
                .privateKey(testResourcePath("/tls/localhost-key.pem"))
                .build();
    }

    private static ClientTlsConfig testClientTls() throws Exception {
        return ClientTlsConfig.builder()
                .trustCertificateChain(testResourcePath("/tls/localhost-cert.pem"))
                .build();
    }

    private static Path testResourcePath(String resource) throws Exception {
        var location = Http2WaveClientIntegrationTest.class.getResource(resource);
        if (location == null) {
            throw new AssertionError("Missing test resource: " + resource);
        }
        return Path.of(location.toURI());
    }

    /**
     * Deliberately small raw tunnel fixture: it records one CONNECT handshake, then only relays
     * bytes. TLS and HTTP/2 therefore remain end-to-end properties of the client and Wave server.
     */
    private static final class ConnectProxy implements AutoCloseable {
        private static final String LOOPBACK = "127.0.0.1";
        private static final int MAXIMUM_HEADER_BYTES = 16 * 1024;

        private final ServerSocket listener;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<String> connectStartLine = new AtomicReference<>();
        private final CountDownLatch connectObserved = new CountDownLatch(1);
        private final Thread acceptor;

        private ConnectProxy() throws IOException {
            listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(LOOPBACK, 0));
            acceptor = Thread.ofVirtual().name("wave-h2-connect-proxy-accept").start(this::acceptLoop);
        }

        static ConnectProxy start() {
            try {
                return new ConnectProxy();
            } catch (IOException failure) {
                throw new IllegalStateException("Could not start CONNECT proxy fixture", failure);
            }
        }

        URI baseUri() {
            return URI.create("http://" + LOOPBACK + ':' + listener.getLocalPort() + '/');
        }

        boolean awaitConnect(long timeout, TimeUnit unit) throws InterruptedException {
            return connectObserved.await(timeout, unit);
        }

        String connectStartLine() {
            return connectStartLine.get();
        }

        void assertHealthy() {
            var observed = failure.get();
            if (observed != null) {
                throw new AssertionError("CONNECT proxy fixture failed", observed);
            }
        }

        @Override
        public void close() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            closeQuietly(listener);
            for (var socket : openSockets) {
                closeQuietly(socket);
            }
        }

        private void acceptLoop() {
            while (running.get()) {
                final Socket client;
                try {
                    client = listener.accept();
                } catch (IOException closed) {
                    if (running.get()) {
                        failure.compareAndSet(null, closed);
                    }
                    return;
                }
                openSockets.add(client);
                Thread.ofVirtual().name("wave-h2-connect-proxy-tunnel").start(() -> serve(client));
            }
        }

        private void serve(Socket client) {
            Socket origin = null;
            var tunnelEstablished = false;
            try (client;
                    var clientInput = new BufferedInputStream(client.getInputStream());
                    var clientOutput = new BufferedOutputStream(client.getOutputStream())) {
                client.setSoTimeout(5_000);
                var requestLine = readLine(clientInput);
                var target = parseConnectTarget(requestLine);
                readHeaders(clientInput);
                connectStartLine.compareAndSet(null, requestLine);
                connectObserved.countDown();

                origin = new Socket();
                openSockets.add(origin);
                origin.connect(new InetSocketAddress(target.getHost(), target.getPort()), 5_000);
                origin.setTcpNoDelay(true);
                clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                clientOutput.flush();
                client.setSoTimeout(0);
                tunnelEstablished = true;

                var targetSocket = origin;
                var clientToOrigin = Thread.ofVirtual().name("wave-h2-connect-proxy-upstream").start(() -> {
                    try {
                        relay(clientInput, targetSocket.getOutputStream());
                    } catch (IOException ignored) {
                        // Closing either tunnel side is normal after a completed test exchange.
                    } finally {
                        closeQuietly(client);
                        closeQuietly(targetSocket);
                    }
                });
                try {
                    relay(targetSocket.getInputStream(), clientOutput);
                } finally {
                    closeQuietly(client);
                    closeQuietly(targetSocket);
                    clientToOrigin.join(5_000);
                }
            } catch (IOException expectedAfterTunnel) {
                if (!tunnelEstablished && running.get()) {
                    failure.compareAndSet(null, expectedAfterTunnel);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, interrupted);
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            } finally {
                openSockets.remove(client);
                if (origin != null) {
                    openSockets.remove(origin);
                    closeQuietly(origin);
                }
            }
        }

        private static URI parseConnectTarget(String requestLine) {
            var pieces = requestLine.split(" ", 3);
            if (pieces.length != 3 || !pieces[0].equals("CONNECT") || !pieces[2].equals("HTTP/1.1")) {
                throw new IllegalArgumentException("Expected an HTTP/1.1 CONNECT request: " + requestLine);
            }
            var target = URI.create("http://" + pieces[1]);
            if (target.getHost() == null || target.getPort() <= 0 || target.getPort() > 65_535
                    || target.getRawPath() != null && !target.getRawPath().isEmpty()) {
                throw new IllegalArgumentException("CONNECT target must be an authority: " + pieces[1]);
            }
            return target;
        }

        private static void readHeaders(InputStream input) throws IOException {
            while (!readLine(input).isEmpty()) {
                // The handshake has no request body; bounded line parsing rejects malformed input.
            }
        }

        private static String readLine(InputStream input) throws IOException {
            var bytes = new ByteArrayOutputStream();
            while (true) {
                var next = input.read();
                if (next < 0) {
                    throw new IOException("CONNECT handshake ended before CRLF");
                }
                if (bytes.size() >= MAXIMUM_HEADER_BYTES) {
                    throw new IOException("CONNECT handshake line exceeds " + MAXIMUM_HEADER_BYTES + " bytes");
                }
                if (next == '\n') {
                    var line = bytes.toByteArray();
                    if (line.length == 0 || line[line.length - 1] != '\r') {
                        throw new IOException("CONNECT handshake line must use CRLF");
                    }
                    return new String(line, 0, line.length - 1, StandardCharsets.ISO_8859_1);
                }
                bytes.write(next);
            }
        }

        private static void relay(InputStream input, OutputStream output) throws IOException {
            var buffer = new byte[8 * 1024];
            for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                output.write(buffer, 0, read);
                output.flush();
            }
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Fixture close is intentionally best effort.
            }
        }
    }
}




