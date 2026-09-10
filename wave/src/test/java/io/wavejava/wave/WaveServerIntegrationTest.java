package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.api.server.TlsConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaveServerIntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void servesJsonRoutesOutsideTheNettyEventLoopAndPreservesRoutingSemantics() throws Exception {
        var handlerWasVirtual = new AtomicBoolean();
        var app = Wave.app().routes(routes -> {
            routes.get("/hello/{name}", (request, response) -> {
                handlerWasVirtual.set(Thread.currentThread().isVirtual());
                response.json(Map.of("message", "hello " + request.pathParameter("name").orElseThrow()));
            });
        }).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var base = URI.create("http://127.0.0.1:" + server.port());
            var success = send(base.resolve("/hello/wave"), "GET", HttpRequest.BodyPublishers.noBody());
            var head = send(base.resolve("/hello/wave"), "HEAD", HttpRequest.BodyPublishers.noBody());
            var methodMismatch = send(base.resolve("/hello/wave"), "POST", HttpRequest.BodyPublishers.noBody());
            var options = send(base.resolve("/hello/wave"), "OPTIONS", HttpRequest.BodyPublishers.noBody());

            assertEquals(200, success.statusCode());
            assertEquals("application/json", success.headers().firstValue("content-type").orElseThrow());
            assertEquals("{\"message\":\"hello wave\"}", success.body());
            assertTrue(handlerWasVirtual.get());
            assertEquals(200, head.statusCode());
            assertEquals("24", head.headers().firstValue("content-length").orElseThrow());
            assertEquals("", head.body());
            assertEquals(405, methodMismatch.statusCode());
            assertEquals("GET, HEAD, OPTIONS", methodMismatch.headers().firstValue("allow").orElseThrow());
            assertEquals(204, options.statusCode());
            assertEquals("GET, HEAD, OPTIONS", options.headers().firstValue("allow").orElseThrow());
            assertTrue(server.isRunning());
        }
    }

    @Test
    void enforcesTheAggregatedBodyBudgetBeforeInvokingTheApplication() throws Exception {
        var invoked = new AtomicBoolean();
        var app = Wave.app().routes(routes -> routes.post("/upload", (request, response) -> {
            invoked.set(true);
            response.text("unreachable");
        })).build();

        try (var server = Wave.server(app).maximumRequestBodyBytes(4).listen(0).start()) {
            var response = send(URI.create("http://127.0.0.1:" + server.port() + "/upload"), "POST",
                    HttpRequest.BodyPublishers.ofString("12345"));

            assertEquals(413, response.statusCode());
            assertFalse(invoked.get());
        }
    }

    @Test
    void compressesGzipResponsesWhenTheClientAcceptsGzip() throws Exception {
        var payload = "wave gzip response ".repeat(512);
        var app = Wave.app().routes(routes -> routes.get("/compressed", (request, response) -> response.text(payload)))
                .build();

        try (var server = Wave.server(app).compression(true).listen(0).start()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/compressed"))
                    .header("Accept-Encoding", "gzip")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals("gzip", response.headers().firstValue("content-encoding").orElseThrow());
            assertEquals(payload, gunzip(response.body()));
        }
    }

    @Test
    void rejectsAConcurrentRequestWhenTheGlobalInFlightBudgetIsExhausted() throws Exception {
        var firstHandlerStarted = new CountDownLatch(1);
        var releaseFirstHandler = new CountDownLatch(1);
        var rejectedHandlerInvoked = new AtomicBoolean();
        var app = Wave.app().routes(routes -> {
            routes.get("/hold", (request, response) -> {
                firstHandlerStarted.countDown();
                await(releaseFirstHandler, "test did not release the admitted request");
                response.text("first");
            });
            routes.get("/second", (request, response) -> {
                rejectedHandlerInvoked.set(true);
                response.text("unreachable");
            });
        }).build();
        var limits = ServerLimits.defaults().toBuilder().maximumInFlightRequests(1).build();

        try (var server = Wave.server(app).limits(limits).listen(0).start()) {
            var base = URI.create("http://127.0.0.1:" + server.port());
            var firstClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var secondClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var firstRequest = HttpRequest.newBuilder(base.resolve("/hold"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var secondRequest = HttpRequest.newBuilder(base.resolve("/second"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var firstResponse = firstClient.sendAsync(firstRequest, HttpResponse.BodyHandlers.ofString());

            try {
                await(firstHandlerStarted, "the first request should consume the only in-flight permit");
                var rejected = secondClient.send(secondRequest, HttpResponse.BodyHandlers.ofString());

                assertEquals(503, rejected.statusCode());
                assertFalse(rejectedHandlerInvoked.get());
            } finally {
                releaseFirstHandler.countDown();
            }

            assertEquals(200, firstResponse.get(5, TimeUnit.SECONDS).statusCode());
        }
    }

    @Test
    void gracefulShutdownCancelsAndInterruptsABlockedHandlerWithinTheConfiguredBudget() throws Exception {
        var shutdownTimeout = Duration.ofSeconds(2);
        var handlerEntered = new CountDownLatch(1);
        var handlerInterrupted = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var cancellationReason = new AtomicReference<String>();
        var app = Wave.app().routes(routes -> routes.get("/hold", (request, response) -> {
            var token = request.cancellationToken().orElseThrow();
            handlerEntered.countDown();
            try {
                releaseHandler.await();
                response.text("released");
            } catch (InterruptedException expected) {
                cancellationReason.set(token.reason().orElse(null));
                handlerInterrupted.countDown();
                response.text("cancelled");
            }
        })).build();
        var timeouts = ServerTimeouts.defaults().toBuilder().shutdownTimeout(shutdownTimeout).build();
        var server = Wave.server(app).timeouts(timeouts).listen(0).start();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/hold"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        var requestFuture = CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        try (var closer = Executors.newVirtualThreadPerTaskExecutor()) {
            await(handlerEntered, "handler should be blocked before shutdown begins");

            var closeFuture = closer.submit(server::close);
            closeFuture.get(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);

            assertTrue(handlerInterrupted.await(5, TimeUnit.SECONDS), "shutdown should interrupt the handler");
            assertEquals("server shutdown", cancellationReason.get());
            assertFalse(server.isRunning());
        } finally {
            releaseHandler.countDown();
            requestFuture.cancel(true);
            server.close();
        }
    }

    @Test
    void closesASlowInboundPeerWhenTheReadTimeoutElapses() throws Exception {
        var readTimeout = Duration.ofMillis(250);
        var timeouts = ServerTimeouts.defaults().toBuilder()
                .readTimeout(readTimeout)
                .idleTimeout(Duration.ofSeconds(2))
                .build();
        var app = Wave.app().build();

        try (var server = Wave.server(app).timeouts(timeouts).listen(0).start();
                var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            awaitPeerClose(socket);
        }
    }

    @Test
    void servesHttpsWithTheTestCertificate() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/secure", (request, response) -> response.text("secure")))
                .build();
        var tls = TlsConfig.builder()
                .certificateChain(testResourcePath("/tls/localhost-cert.pem"))
                .privateKey(testResourcePath("/tls/localhost-key.pem"))
                .build();

        try (var server = Wave.server(app).tls(tls).listen(0).start()) {
            var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/secure"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = trustedClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("secure", response.body());
            assertTrue(server.isRunning());
        }
    }

    @Test
    void tlsStartupFailsWhenCertificateMaterialDoesNotExist(@TempDir Path temporaryDirectory) {
        var app = Wave.app().build();
        var tls = TlsConfig.builder()
                .certificateChain(temporaryDirectory.resolve("missing-certificate.pem"))
                .privateKey(temporaryDirectory.resolve("missing-private-key.pem"))
                .build();

        var failure = assertThrows(IllegalStateException.class, () -> Wave.server(app).tls(tls).listen(0).start());

        assertEquals("Could not create server TLS context from configured certificate material", failure.getMessage());
    }

    private static HttpResponse<String> send(URI uri, String method, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).method(method, body).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient trustedClient() throws GeneralSecurityException {
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
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(context)
                .build();
    }

    private static Path testResourcePath(String resource) throws Exception {
        var location = WaveServerIntegrationTest.class.getResource(resource);
        if (location == null) {
            throw new AssertionError("Missing test resource: " + resource);
        }
        return Path.of(location.toURI());
    }

    private static String gunzip(byte[] compressed) throws IOException {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void awaitPeerClose(Socket socket) throws IOException {
        try {
            while (socket.getInputStream().read() != -1) {
                // A timeout abort may produce an error response before the transport closes.
            }
        } catch (SocketException expected) {
            // TCP reset is an equally valid close outcome for a timed-out peer.
        }
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), message);
    }
}
