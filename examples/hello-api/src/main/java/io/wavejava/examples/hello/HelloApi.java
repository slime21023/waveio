package io.wavejava.examples.hello;

import io.wavejava.wave.Wave;
import io.wavejava.wave.WaveApp;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Smallest bounded Wave consumer: one route, one request, one shutdown. */
public final class HelloApi {
    private HelloApi() {
    }

    public static WaveApp application() {
        return Wave.app().routes(routes -> {
            routes.get("/hello", (request, response) ->
                    response.text("hello, " + request.queryParameters("name").stream().findFirst().orElse("wave")));
            routes.get("/failure", (request, response) -> {
                throw new IllegalStateException("documented example failure");
            });
        }).build();
    }

    public static void main(String[] args) throws Exception {
        try (var server = server(); var client = HttpClient.newHttpClient()) {
            var target = URI.create("http://127.0.0.1:" + server.port() + "/hello?name=consumer");
            var response = client.send(HttpRequest.newBuilder(target).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.body().contains("consumer")) {
                throw new IllegalStateException("hello example smoke failed: " + response.statusCode());
            }
            var failure = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/failure")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (failure.statusCode() != 500) {
                throw new IllegalStateException("hello failure smoke failed: " + failure.statusCode());
            }
            System.out.println(response.body());
        }
    }

    private static io.wavejava.wave.RunningServer server() {
        return Wave.server(application())
                .limits(ServerLimits.builder().maximumConnections(32).maximumInFlightRequests(32).build())
                .timeouts(ServerTimeouts.builder().requestTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(5)).writeTimeout(Duration.ofSeconds(5))
                        .idleTimeout(Duration.ofSeconds(10)).shutdownTimeout(Duration.ofSeconds(5)).build())
                .listen(0)
                .start();
    }
}
