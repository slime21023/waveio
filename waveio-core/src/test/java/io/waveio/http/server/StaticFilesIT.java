package io.waveio.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.waveio.http.testing.RawHttpClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Static file serving is a security surface before it is a convenience: the natural hand-written
 * version escapes its root. These cases are mostly about what must NOT be served.
 */
class StaticFilesIT {

    @TempDir
    Path workspace;

    @Test
    void servesAFileWithItsContentTypeAndLastModified() throws Exception {
        var root = publicRoot();
        Files.writeString(root.resolve("app.css"), "body{color:red}");

        try (var server = server(root); var client = RawHttpClient.connect(port(server))) {
            client.send(get("/assets/app.css"));

            var response = client.readResponse();
            assertEquals(200, response.status());
            assertEquals("body{color:red}", response.bodyText());
            assertEquals("text/css", response.header("content-type").orElseThrow());
            assertTrue(response.header("last-modified").isPresent());
        }
    }

    @Test
    void servesFilesFromNestedDirectories() throws Exception {
        var root = publicRoot();
        Files.createDirectories(root.resolve("js/vendor"));
        Files.writeString(root.resolve("js/vendor/lib.js"), "export {};");

        try (var server = server(root); var client = RawHttpClient.connect(port(server))) {
            client.send(get("/assets/js/vendor/lib.js"));

            var response = client.readResponse();
            assertEquals(200, response.status());
            assertEquals("export {};", response.bodyText());
            assertEquals("text/javascript", response.header("content-type").orElseThrow());
        }
    }

    @Test
    void refusesToEscapeTheRootWithDotSegments() throws Exception {
        var root = publicRoot();
        Files.writeString(workspace.resolve("secret.txt"), "top secret");

        try (var server = server(root); var client = RawHttpClient.connect(port(server))) {
            client.send(get("/assets/../secret.txt"));

            var response = client.readResponse();
            assertTrue(response.status() == 400 || response.status() == 404,
                    "escape must never succeed, got " + response.status());
            assertTrue(!response.bodyText().contains("top secret"));
        }
    }

    @Test
    void refusesToEscapeTheRootWithAnAbsoluteLookingSegment() throws Exception {
        var root = publicRoot();

        try (var server = server(root); var client = RawHttpClient.connect(port(server))) {
            client.send(get("/assets/C:/Windows/win.ini"));

            var response = client.readResponse();
            assertTrue(response.status() == 400 || response.status() == 404,
                    "escape must never succeed, got " + response.status());
        }
    }

    @Test
    void refusesToFollowASymbolicLinkOutOfTheRoot() throws Exception {
        var root = publicRoot();
        var outside = workspace.resolve("outside.txt");
        Files.writeString(outside, "top secret");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "symbolic links are not available in this environment");
        }

        try (var server = server(root); var client = RawHttpClient.connect(port(server))) {
            client.send(get("/assets/link.txt"));

            var response = client.readResponse();
            assertEquals(404, response.status());
            assertTrue(!response.bodyText().contains("top secret"));
        }
    }

    @Test
    void answersNotFoundForMissingFilesAndDirectories() throws Exception {
        var root = publicRoot();
        Files.createDirectories(root.resolve("js"));

        try (var server = server(root); var client = RawHttpClient.connect(port(server))) {
            client.send(get("/assets/missing.txt"));
            assertEquals(404, client.readResponse().status());

            client.send(get("/assets/js"));
            assertEquals(404, client.readResponse().status());
        }
    }

    @Test
    void answersNotModifiedWhenTheClientAlreadyHasTheFile() throws Exception {
        var root = publicRoot();
        var file = root.resolve("app.css");
        Files.writeString(file, "body{color:red}");

        try (var server = server(root); var client = RawHttpClient.connect(port(server))) {
            client.send(get("/assets/app.css"));
            String lastModified = client.readResponse().header("last-modified").orElseThrow();

            client.send("GET /assets/app.css HTTP/1.1\r\nHost: localhost\r\n"
                    + "If-Modified-Since: " + lastModified + "\r\n\r\n");
            var response = client.readResponse();

            assertEquals(304, response.status());
            assertEquals(0, response.body().length);
        }
    }

    @Test
    void servesNothingWhenTheClientIsAheadOfTheFile() throws Exception {
        var root = publicRoot();
        Files.writeString(root.resolve("app.css"), "body{color:red}");
        String future = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).plusDays(1));

        try (var server = server(root); var client = RawHttpClient.connect(port(server))) {
            client.send("GET /assets/app.css HTTP/1.1\r\nHost: localhost\r\n"
                    + "If-Modified-Since: " + future + "\r\n\r\n");

            assertEquals(304, client.readResponse().status());
        }
    }

    private Path publicRoot() throws IOException {
        return Files.createDirectories(workspace.resolve("public"));
    }

    private static HttpServer server(Path root) {
        return HttpServer.builder()
                .port(0)
                .router(io.waveio.http.routing.Router.builder()
                        .staticFiles("/assets", root)
                        .build())
                .build()
                .start();
    }

    private static int port(HttpServer server) {
        return server.localPort();
    }

    private static String get(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
    }
}
