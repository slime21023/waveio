package io.wavejava.wave.api.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.Http2Config;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Verifies that a received GOAWAY retires its parent before a later exchange opens a stream. */
class Http2GoAwayClientIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void retiresTheGoAwayParentAndOpensANewParentForTheNextExchange() throws Exception {
        try (var origin = TestHttp2GoAwayOrigin.start(
                        testResourcePath("/tls/localhost-cert.pem"),
                        testResourcePath("/tls/localhost-key.pem"));
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder()
                        .http2(Http2Config.builder().build())
                        .tls(ClientTlsConfig.builder()
                                .trustCertificateChain(testResourcePath("/tls/localhost-cert.pem"))
                                .build())
                        .build())) {
            var base = URI.create("https://127.0.0.1:" + origin.port());
            var first = client.execute(ClientRequest.get(base.resolve("/first")));

            assertEquals("parent-1:/first", first.text());
            assertTrue(origin.awaitGoAway(TIMEOUT), "the origin should write GOAWAY before completing the first stream");

            var second = client.execute(ClientRequest.get(base.resolve("/second")));
            assertEquals("parent-2:/second", second.text());
            assertTrue(origin.awaitSecondParent(TIMEOUT),
                    "the exchange after GOAWAY must use a newly negotiated HTTP/2 parent");
            assertEquals(2, origin.parentConnectionCount());
            origin.assertHealthy();
        }
    }

    @Test
    void handsOffToANewParentWhileTheGoAwayAcceptedStreamDrains() throws Exception {
        var http2 = Http2Config.builder().maximumConcurrentStreams(1).build();
        var pool = ClientRequestPool.builder().maximumConcurrentRequests(2).build();
        try (var origin = TestHttp2GoAwayOrigin.startHoldingFirstResponse(
                        testResourcePath("/tls/localhost-cert.pem"),
                        testResourcePath("/tls/localhost-key.pem"));
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder()
                        .requestPool(pool)
                        .http2(http2)
                        .tls(ClientTlsConfig.builder()
                                .trustCertificateChain(testResourcePath("/tls/localhost-cert.pem"))
                                .build())
                        .build())) {
            var base = URI.create("https://127.0.0.1:" + origin.port());
            var first = client.executeAsync(ClientRequest.get(base.resolve("/first"))).toCompletableFuture();

            assertTrue(origin.awaitGoAway(TIMEOUT), "the origin should retire the first parent before completing stream 1");
            assertTrue(origin.awaitFirstResponseHeld(TIMEOUT), "stream 1 should remain drainable after GOAWAY");
            assertFalse(first.isDone(), "the accepted stream must still be active while its response is held");

            var second = client.execute(ClientRequest.get(base.resolve("/second")));
            assertEquals("parent-2:/second", second.text());
            assertTrue(origin.awaitSecondParent(TIMEOUT),
                    "the post-GOAWAY exchange must use a new parent before stream 1 drains");
            assertFalse(first.isDone(), "servicing the replacement parent must not abort the accepted stream");

            origin.releaseFirstResponse();
            assertEquals("parent-1:/first", first.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).text());
            assertEquals(2, origin.parentConnectionCount());
            origin.assertHealthy();
        }
    }

    private static Path testResourcePath(String resource) throws Exception {
        var location = Http2GoAwayClientIntegrationTest.class.getResource(resource);
        if (location == null) {
            throw new AssertionError("Missing test resource: " + resource);
        }
        return Path.of(location.toURI());
    }
}


