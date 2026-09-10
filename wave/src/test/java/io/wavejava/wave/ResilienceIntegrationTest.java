package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.resilience.BulkheadPolicy;
import io.wavejava.wave.api.resilience.RateLimitPolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ResilienceIntegrationTest {
    @Test
    void rateLimitAndBulkheadRejectOverloadAcrossRealSocketConnections() throws Exception {
        var rate = RateLimitPolicy.builder()
                .capacity(1)
                .tokensPerPeriod(1)
                .refillPeriod(Duration.ofMinutes(1))
                .keyer(request -> request.header("X-Client").orElse("anonymous"))
                .build();
        var bulkhead = new BulkheadPolicy(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var app = Wave.app().middleware(rate).middleware(bulkhead).routes(routes -> {
            routes.get("/rate", (request, response) -> response.text("rate"));
            routes.get("/hold", (request, response) -> {
                entered.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                response.text("held");
            });
        }).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var port = server.port();
            var rateClient = HttpClient.newHttpClient();
            assertEquals(200, send(rateClient, port, "/rate", "tenant").statusCode());
            var limited = send(rateClient, port, "/rate", "tenant");
            assertEquals(429, limited.statusCode());
            assertEquals("60", limited.headers().firstValue("Retry-After").orElseThrow());

            var firstClient = HttpClient.newHttpClient();
            var first = firstClient.sendAsync(request(port, "/hold", "one"), HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var secondClient = HttpClient.newHttpClient();
            assertEquals(503, secondClient.send(request(port, "/hold", "two"), HttpResponse.BodyHandlers.ofString()).statusCode());
            release.countDown();
            assertEquals(200, first.get(2, TimeUnit.SECONDS).statusCode());
            assertEquals(0, bulkhead.activeInvocations());
        }
    }

    @Test
    void serverShutdownInterruptsBlockedBulkheadWorkBeforePermitsAreReused() throws Exception {
        var bulkhead = new BulkheadPolicy(1);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var app = Wave.app().middleware(bulkhead).routes(routes -> routes.get("/block", (request, response) -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            response.text("finished");
        })).build();
        var server = Wave.server(app).listen(0).start();
        try {
            HttpClient.newHttpClient().sendAsync(request(server.port(), "/block", "one"), HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            server.close();
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(0, bulkhead.activeInvocations());
        } finally {
            server.close();
        }
    }

    private static HttpResponse<String> send(HttpClient client, int port, String path, String identity) throws Exception {
        return client.send(request(port, path, identity), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(int port, String path, String identity) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Client", identity)
                .GET()
                .build();
    }
}
