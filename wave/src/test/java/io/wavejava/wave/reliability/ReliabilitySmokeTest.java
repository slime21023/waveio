package io.wavejava.wave.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.wavejava.wave.Wave;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Finite, opt-in socket smoke used by the scheduled reliability profile. */
@Tag("reliability")
class ReliabilitySmokeTest {
    @Test
    void boundedConcurrentTrafficSurvivesAndShutsDown() throws Exception {
        var app = Wave.app().routes(routes -> {
            routes.get("/ok", (request, response) -> response.text("ok"));
            routes.get("/failure", (request, response) -> {
                throw new IllegalStateException("expected application failure");
            });
        }).build();
        var server = Wave.server(app).listen(0).start();
        try (var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build()) {
            var base = URI.create("http://127.0.0.1:" + server.port());
            var waves = Integer.getInteger("wave.reliability.waves", 1);
            if (waves < 1 || waves > 600) {
                throw new IllegalArgumentException("wave.reliability.waves must be between 1 and 600");
            }
            for (var wave = 0; wave < waves; wave++) {
                var requests = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
                for (var index = 0; index < 128; index++) {
                    var path = index % 16 == 0 ? "/failure" : "/ok";
                    requests.add(client.sendAsync(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                            HttpResponse.BodyHandlers.ofString()));
                }
                var responses = new ArrayList<HttpResponse<String>>();
                for (var request : requests) {
                    responses.add(request.get(10, TimeUnit.SECONDS));
                }
                var failures = responses.stream().filter(response -> response.statusCode() == 500).count();
                assertEquals(8, failures, "application failures in wave " + wave);
                assertEquals(120, responses.stream()
                        .filter(response -> response.statusCode() == 200 && "ok".equals(response.body()))
                        .count(), "successful response bodies in wave " + wave);
            }
        } finally {
            server.close();
        }
        org.junit.jupiter.api.Assertions.assertFalse(server.isRunning());
    }
}
