package io.wavejava.benchmarks;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Finite server workload used by the memory-observation gate.
 *
 * <p>The target deliberately keeps one bounded batch in flight so a memory sample describes the
 * Wave transport and dispatch path rather than an unbounded client queue.
 */
public final class WaveServerReliabilityTarget {
    private WaveServerReliabilityTarget() {
    }

    public static void main(String[] args) throws Exception {
        var durationSeconds = boundedProperty("wave.benchmark.durationSeconds", 20, 1, 600);
        var concurrency = boundedProperty("wave.benchmark.concurrency", 32, 1, 128);
        var app = Wave.app().routes(routes -> {
            routes.get("/ok", (request, response) -> response.json(Map.of("ok", true)));
            routes.get("/failure", (request, response) -> {
                throw new IllegalStateException("expected reliability failure");
            });
        }).build();
        var limits = ServerLimits.builder()
                .maximumConnections(64)
                .maximumInFlightRequests(64)
                .maximumPendingRequestsPerConnection(32)
                .maximumPendingResponseBytesPerConnection(256 * 1024)
                .build();
        var timeouts = ServerTimeouts.builder()
                .requestTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(5))
                .idleTimeout(Duration.ofSeconds(10))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        var completed = 0L;
        var successes = 0L;
        var failures = 0L;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
        try (var server = Wave.server(app).limits(limits).timeouts(timeouts).listen(0).start();
                var client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build()) {
            var endpoint = URI.create("http://127.0.0.1:" + server.port());
            while (System.nanoTime() < deadline) {
                var batch = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>(concurrency);
                for (var index = 0; index < concurrency; index++) {
                    var path = index % 16 == 0 ? "/failure" : "/ok";
                    batch.add(client.sendAsync(
                            HttpRequest.newBuilder(endpoint.resolve(path)).GET().build(),
                            HttpResponse.BodyHandlers.ofString()));
                }
                for (var request : batch) {
                    var response = request.get(10, TimeUnit.SECONDS);
                    completed++;
                    if (response.statusCode() == 200 && "{\"ok\":true}".equals(response.body())) {
                        successes++;
                    } else if (response.statusCode() == 200) {
                        throw new IllegalStateException("unexpected success response body: " + response.body());
                    } else if (response.statusCode() >= 500) {
                        failures++;
                    } else {
                        throw new IllegalStateException("unexpected reliability response status: "
                                + response.statusCode());
                    }
                }
            }
        }
        if (completed == 0 || successes == 0 || failures == 0) {
            throw new IllegalStateException("server reliability target did not exercise success and failure paths"
                    + " (completed=" + completed + ", successes=" + successes + ", failures=" + failures + ')');
        }
        System.out.println("Wave server target completed requests=" + completed
                + " successes=" + successes + " failures=" + failures);
    }

    private static int boundedProperty(String name, int defaultValue, int minimum, int maximum) {
        var value = Integer.getInteger(name, defaultValue);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }
}
