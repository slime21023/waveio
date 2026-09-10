package io.wavejava.examples.forms;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.file.StaticFileHandler;
import io.wavejava.wave.api.form.FormData;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Bounded URL-encoded form and static-file example. */
public final class FormsAndFiles {
    private FormsAndFiles() {
    }

    public static WaveApp application(Path documentRoot) {
        var files = StaticFileHandler.builder(documentRoot).pathParameter("path").maximumBytes(64 * 1024).build();
        return Wave.app().routes(routes -> {
            routes.post("/form", (request, response) -> {
                FormData form = new io.wavejava.wave.api.form.UrlEncodedFormParser().parse(request.body());
                response.text(form.first("name").orElse("missing"));
            });
            routes.get("/{*path}", files);
        }).build();
    }

    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("wave-example-");
        try {
            Files.writeString(root.resolve("index.txt"), "wave file");
            try (var server = server(root); var client = HttpClient.newHttpClient()) {
                var formTarget = URI.create("http://127.0.0.1:" + server.port() + "/form");
                var fileTarget = URI.create("http://127.0.0.1:" + server.port() + "/index.txt");
                var form = client.send(HttpRequest.newBuilder(formTarget)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("name=consumer"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                var file = client.send(HttpRequest.newBuilder(fileTarget).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (form.statusCode() != 200 || !form.body().equals("consumer") || file.statusCode() != 200) {
                    throw new IllegalStateException("forms-and-files example smoke failed");
                }
                System.out.println(form.body() + " / " + file.body());
            }
        } finally {
            Files.deleteIfExists(root.resolve("index.txt"));
            Files.deleteIfExists(root);
        }
    }

    private static io.wavejava.wave.api.server.RunningServer server(Path root) {
        return io.wavejava.wave.Wave.server(application(root))
                .limits(ServerLimits.builder().maximumConnections(16).maximumInFlightRequests(16)
                        .maximumRequestBodyBytes(64 * 1024).build())
                .timeouts(ServerTimeouts.builder().requestTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(5)).writeTimeout(Duration.ofSeconds(5))
                        .idleTimeout(Duration.ofSeconds(10)).shutdownTimeout(Duration.ofSeconds(5)).build())
                .listen(0)
                .start();
    }
}
