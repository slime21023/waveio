package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import io.wavejava.wave.api.server.Http2Config;
import io.wavejava.wave.api.server.TlsConfig;
import io.wavejava.wave.netty.TestHttp2Peer;
import io.wavejava.wave.netty.TestHttp2WirePeer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Raw-frame TLS/ALPN conformance coverage that a high-level JDK client cannot express. */
class Http2ProtocolConformanceIntegrationTest {
    @Test
    void rejectsConnectionSpecificHeadersBeforeApplicationResult() throws Exception {
        var invocations = new AtomicInteger();
        var app = Wave.app().routes(routes -> routes.get("/must-not-run", (request, response) -> {
            invocations.incrementAndGet();
            response.text("unexpected");
        })).build();

        try (var server = Wave.server(app).tls(testTls()).http2(Http2Config.builder().build()).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testCertificate())) {
            var invalid = TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/must-not-run");
            invalid.add("connection", "keep-alive");
            var stream = peer.open(invalid, true);

            stream.awaitTerminal(Duration.ofSeconds(5));
            assertEquals(0, invocations.get(), "forbidden HTTP/2 headers must never reach the handler");
            assertTrue(stream.reset().isDone() || stream.closed().isDone() || stream.response().isDone(),
                    "server must terminate or reject the invalid HTTP/2 stream");
        }
    }

    @Test
    void rejectsIllegalTeValueBeforeApplicationResult() throws Exception {
        var invocations = new AtomicInteger();
        var app = Wave.app().routes(routes -> routes.get("/must-not-run", (request, response) -> {
            invocations.incrementAndGet();
            response.text("unexpected");
        })).build();

        try (var server = Wave.server(app).tls(testTls()).http2(Http2Config.builder().build()).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testCertificate())) {
            var invalid = TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/must-not-run");
            invalid.add("te", "gzip");
            var stream = peer.open(invalid, true);

            stream.awaitTerminal(Duration.ofSeconds(5));
            assertEquals(0, invocations.get(), "only TE: trailers is legal on an HTTP/2 request");
        }
    }

    @Test
    void rejectsAnOversizedHttp2HeaderListBeforeApplicationResult() throws Exception {
        var invocations = new AtomicInteger();
        var app = Wave.app().routes(routes -> routes.get("/must-not-run", (request, response) -> {
            invocations.incrementAndGet();
            response.text("unexpected");
        })).build();
        var http2 = Http2Config.builder().maximumHeaderListBytes(16 * 1024).build();

        try (var server = Wave.server(app).tls(testTls()).http2(http2).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testCertificate())) {
            var invalid = TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/must-not-run");
            invalid.add("x-oversized", "x".repeat(17 * 1024));
            var stream = peer.open(invalid, true);

            stream.awaitTerminal(Duration.ofSeconds(5));
            assertEquals(0, invocations.get(), "an over-limit header list must not reach application code");
        }
    }

    @Test
    void rejectsADuplicatePseudoHeaderBeforeApplicationResult() throws Exception {
        var invocations = new AtomicInteger();
        var app = Wave.app().routes(routes -> routes.get("/must-not-run", (request, response) -> {
            invocations.incrementAndGet();
            response.text("unexpected");
        })).build();

        try (var server = Wave.server(app).tls(testTls()).http2(Http2Config.builder().build()).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testCertificate())) {
            // The raw test peer disables outbound validation precisely so this duplicate reaches
            // Wave's server decoder instead of being rejected locally by the fixture.
            var invalid = TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/must-not-run");
            invalid.add(":path", "/second-path");
            var stream = peer.open(invalid, true);

            stream.awaitTerminal(Duration.ofSeconds(5));
            assertEquals(0, invocations.get(), "a duplicate pseudo-header must never reach the handler");
        }
    }

    @Test
    void rejectsConflictingHttp2ContentLengthsBeforeApplicationResult() throws Exception {
        var invocations = new AtomicInteger();
        var app = Wave.app().routes(routes -> routes.get("/must-not-run", (request, response) -> {
            invocations.incrementAndGet();
            response.text("unexpected");
        })).build();

        try (var server = Wave.server(app).tls(testTls()).http2(Http2Config.builder().build()).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testCertificate())) {
            var invalid = TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/must-not-run");
            invalid.add("content-length", "1");
            invalid.add("content-length", "2");
            var stream = peer.open(invalid, true);

            stream.awaitTerminal(Duration.ofSeconds(5));
            assertEquals(0, invocations.get(), "conflicting HTTP/2 content lengths must never reach the handler");
        }
    }

    @Test
    void sendsGoAwayAfterARstStreamFloodBeforeApplicationResult() throws Exception {
        var invocations = new AtomicInteger();
        var app = Wave.app().routes(routes -> routes.post("/must-not-run", (request, response) -> {
            invocations.incrementAndGet();
            response.text("unexpected");
        })).build();

        try (var server = Wave.server(app).tls(testTls()).http2(Http2Config.builder().build()).listen(0).start();
                var peer = TestHttp2Peer.connect(server.port(), testCertificate())) {
            var authority = "127.0.0.1:" + server.port();
            var streams = new ArrayList<TestHttp2Peer.RawStream>();
            for (var index = 0; index < 17; index++) {
                var headers = TestHttp2Peer.requestHeaders(authority, "/must-not-run");
                headers.method("POST");
                streams.add(peer.open(headers, false));
            }
            for (var stream : streams) {
                stream.sendReset();
            }

            var goAway = peer.awaitGoAway(Duration.ofSeconds(5));
            assertEquals(io.netty.handler.codec.http2.Http2Error.ENHANCE_YOUR_CALM.code(), goAway.errorCode());
            assertEquals(Integer.MAX_VALUE, goAway.lastStreamId(),
                    "Netty must use a hard GOAWAY boundary for an RST-frame rate violation");
            peer.awaitParentClose(Duration.ofSeconds(5));
            assertEquals(0, invocations.get(),
                    "the RST flood guard must terminate before an aggregate request is dispatched");
        }
    }

    @Test
    void sendsGoAwayAfterConsecutiveEmptyDataFramesBeforeApplicationResult() throws Exception {
        var invocations = new AtomicInteger();
        var app = Wave.app().routes(routes -> routes.post("/must-not-run", (request, response) -> {
            invocations.incrementAndGet();
            response.text("unexpected");
        })).build();

        try (var server = Wave.server(app).tls(testTls()).http2(Http2Config.builder().build()).listen(0).start();
                var peer = TestHttp2WirePeer.connect(server.port(), testCertificate())) {
            peer.sendConsecutiveEmptyDataFrames("127.0.0.1:" + server.port(), 9);

            var goAway = peer.awaitGoAway(Duration.ofSeconds(5));
            assertEquals(io.netty.handler.codec.http2.Http2Error.ENHANCE_YOUR_CALM.code(), goAway.errorCode());
            peer.awaitParentClose(Duration.ofSeconds(5));
            assertEquals(0, invocations.get(),
                    "the empty-data flood guard must terminate before an aggregate request is dispatched");
        }
    }

    @Test
    void appliesOnlyOneStrictTrustedForwardedOriginOnAnHttp2Stream() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/public", (request, response) ->
                response.text(request.publicAddress().orElseThrow().origin().toString()))).build();
        var trusted = ForwardedHeaderPolicy.builder().trustedProxy("127.0.0.0/8").build();

        try (var server = Wave.server(app)
                        .tls(testTls())
                        .http2(Http2Config.builder().build())
                        .forwardedHeaders(trusted)
                        .listen(0)
                        .start();
                var peer = TestHttp2Peer.connect(server.port(), testCertificate())) {
            var valid = TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/public");
            valid.add("forwarded", "proto=https;host=public.example:8443");
            assertEquals("https://public.example:8443", peer.open(valid, true).response()
                    .get(5, TimeUnit.SECONDS).text());

            var ambiguous = TestHttp2Peer.requestHeaders("127.0.0.1:" + server.port(), "/public");
            ambiguous.add("forwarded", "proto=https;host=first.example,proto=https;host=second.example");
            assertEquals("https://127.0.0.1:" + server.port(), peer.open(ambiguous, true).response()
                    .get(5, TimeUnit.SECONDS).text());
        }
    }

    private static TlsConfig testTls() throws Exception {
        return TlsConfig.builder()
                .certificateChain(testCertificate())
                .privateKey(testResourcePath("/tls/localhost-key.pem"))
                .build();
    }

    private static Path testCertificate() throws Exception {
        return testResourcePath("/tls/localhost-cert.pem");
    }

    private static Path testResourcePath(String resource) throws Exception {
        var location = Http2ProtocolConformanceIntegrationTest.class.getResource(resource);
        if (location == null) {
            throw new AssertionError("Missing test resource: " + resource);
        }
        return Path.of(location.toURI());
    }
}

