package io.wavejava.wave.api.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.internal.http.ResponseDataReader;
import io.wavejava.wave.testing.EmbeddedApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticFileHandlerTest {
    @TempDir
    Path root;

    @Test
    void servesBoundedFilesWithValidatorsAndRanges() throws Exception {
        var file = root.resolve("hello.txt");
        Files.writeString(file, "hello wave", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-09-09T00:00:00Z")));
        var app = appFor(StaticFileHandler.builder(root).pathParameter("path").build());

        var full = app.handle(Request.of(HttpMethod.GET, "/hello.txt"));
        var etag = full.headers().first("ETag").orElseThrow();
        assertEquals(200, full.status());
        assertEquals("hello wave", body(full));
        assertEquals("bytes", full.headers().first("Accept-Ranges").orElseThrow());

        var notModified = app.handle(Request.builder()
                .method(HttpMethod.GET)
                .path("/hello.txt")
                .header("If-None-Match", etag)
                .build());
        assertEquals(304, notModified.status());

        var ranged = app.handle(Request.builder()
                .method(HttpMethod.GET)
                .path("/hello.txt")
                .header("Range", "bytes=6-9")
                .build());
        assertEquals(206, ranged.status());
        assertEquals("wave", body(ranged));
        assertEquals("bytes 6-9/10", ranged.headers().first("Content-Range").orElseThrow());
    }

    @Test
    void rejectsTraversalAndHandlesMissingTooLargeAndUnsatisfiableFiles() throws Exception {
        var file = root.resolve("large.txt");
        Files.writeString(file, "12345", StandardCharsets.UTF_8);
        var handler = StaticFileHandler.builder(root).pathParameter("path").maximumBytes(4).build();
        var app = appFor(handler);

        assertEquals(403, app.handle(Request.of(HttpMethod.GET, "/../outside.txt")).status());
        assertEquals(404, app.handle(Request.of(HttpMethod.GET, "/missing.txt")).status());
        assertEquals(413, app.handle(Request.of(HttpMethod.GET, "/large.txt")).status());

        var unrestricted = appFor(StaticFileHandler.builder(root).pathParameter("path").build());
        var unsatisfiable = unrestricted.handle(Request.builder()
                .method(HttpMethod.GET)
                .path("/large.txt")
                .header("Range", "bytes=99-")
                .build());
        assertEquals(416, unsatisfiable.status());
        assertEquals("bytes */5", unsatisfiable.headers().first("Content-Range").orElseThrow());
    }

    @Test
    void exposesConditionalAndRangeFileSemanticsOverARealHttpSocket() throws Exception {
        var file = root.resolve("socket.txt");
        Files.writeString(file, "transport coverage", StandardCharsets.UTF_8);
        var app = appFor(StaticFileHandler.builder(root).pathParameter("path").build());
        var client = HttpClient.newHttpClient();

        try (var embedded = EmbeddedApp.start(app)) {
            var target = URI.create("http://127.0.0.1:" + embedded.port() + "/socket.txt");
            var full = send(client, target, HttpRequest.newBuilder(target).GET().build());
            var etag = full.headers().firstValue("etag").orElseThrow();

            var notModified = send(client, target, HttpRequest.newBuilder(target)
                    .header("If-None-Match", etag)
                    .GET()
                    .build());
            var ranged = send(client, target, HttpRequest.newBuilder(target)
                    .header("Range", "bytes=10-17")
                    .GET()
                    .build());

            assertEquals(200, full.statusCode());
            assertEquals("transport coverage", full.body());
            assertEquals(304, notModified.statusCode());
            assertEquals("", notModified.body());
            assertEquals(206, ranged.statusCode());
            assertEquals("coverage", ranged.body());
            assertEquals("bytes 10-17/18", ranged.headers().firstValue("content-range").orElseThrow());
        }
    }

    private static io.wavejava.wave.WaveApp appFor(StaticFileHandler handler) {
        return Wave.app().routes(routes -> routes.get("/{*path}", handler)).build();
    }

    private static String body(Response response) {
        return new String(ResponseDataReader.read(response).byteContent().orElseThrow(), StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> send(HttpClient client, URI target, HttpRequest request) throws Exception {
        assertEquals(target, request.uri());
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}

