package io.wavejava.wave.api.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.wavejava.wave.Wave;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ClientHeaderBudgetIntegrationTest {
    @Test
    void rejectsAResponseWhoseLogicalHeaderBytesExceedTheConfiguredBudget() {
        var app = Wave.app().routes(routes -> routes.get("/bytes", (request, response) -> response
                .header("X-Long", "x".repeat(128))
                .text("ok"))).build();
        var pool = ClientRequestPool.builder().maximumResponseHeaderBytes(32).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build())) {
            var failure = assertThrows(ClientLimitExceededException.class,
                    () -> client.execute(ClientRequest.get(uri(server.port(), "/bytes"))));

            assertEquals("response header bytes", failure.limitName());
            assertEquals(32, failure.limit());
        } finally {
            pool.close();
        }
    }

    @Test
    void rejectsAResponseWhoseHeaderFieldCountExceedsTheConfiguredBudget() {
        var app = Wave.app().routes(routes -> routes.get("/count", (request, response) -> response
                .header("X-One", "one")
                .header("X-Two", "two")
                .text("ok"))).build();
        var pool = ClientRequestPool.builder().maximumResponseHeaders(1).build();

        try (var server = Wave.server(app).listen(0).start();
                var client = io.wavejava.wave.Wave.client(WaveClientOptions.builder().requestPool(pool).build())) {
            var failure = assertThrows(ClientLimitExceededException.class,
                    () -> client.execute(ClientRequest.get(uri(server.port(), "/count"))));

            assertEquals("response header fields", failure.limitName());
            assertEquals(1, failure.limit());
        } finally {
            pool.close();
        }
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}


