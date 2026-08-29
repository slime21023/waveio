package io.waveio.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpException;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HttpServerBuilderIT {

    @Test
    void configuresAllBuilderPropertiesAndShortcutRoutes() throws Exception {
        var server = HttpServer.builder()
                .host("127.0.0.1")
                .port(0)
                .maxInitialLineLength(2048)
                .maxHeaderSize(4096)
                .maxBodySize(512)
                .maxPendingRequestsPerConnection(4)
                .maxConnections(32)
                .idleTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(15))
                .handlerTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .shutdownTimeout(Duration.ofSeconds(2))
                .exceptionHandler((err, req) -> HttpResponse.status(HttpStatus.of(500, "Err")).body("custom-err"))
                .head("/head", req -> HttpResponse.noContent())
                .put("/put", req -> HttpResponse.text("put"))
                .patch("/patch", req -> HttpResponse.text("patch"))
                .delete("/delete", req -> HttpResponse.text("delete"))
                .options("/options", req -> HttpResponse.text("options"))
                .blockingPut("/b-put", req -> HttpResponse.text("b-put"))
                .blockingPatch("/b-patch", req -> HttpResponse.text("b-patch"))
                .blockingDelete("/b-delete", req -> HttpResponse.text("b-delete"))
                .blockingPost("/b-post", req -> HttpResponse.text("b-post"))
                .asyncGet("/async-get", req -> CompletableFuture.completedFuture(
                        HttpResponse.text("async-get")))
                .build();

        try (server) {
            server.start();
            assertTrue(server.isRunning());

            var client = HttpClient.newHttpClient();
            var uri = URI.create("http://127.0.0.1:" + server.localPort());

            var putResp = client.send(java.net.http.HttpRequest.newBuilder(uri.resolve("/put"))
                    .PUT(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.ofString());
            assertEquals("put", putResp.body());

            var bPutResp = client.send(java.net.http.HttpRequest.newBuilder(uri.resolve("/b-put"))
                    .PUT(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.ofString());
            assertEquals("b-put", bPutResp.body());

            var asyncResp = client.send(java.net.http.HttpRequest.newBuilder(uri.resolve("/async-get"))
                    .GET().build(), BodyHandlers.ofString());
            assertEquals("async-get", asyncResp.body());
        }
    }

    @Test
    void defaultExceptionHandlerFormatsHttpExceptionAndGenericException() throws Exception {
        try (var server = HttpServer.builder()
                .port(0)
                .get("/bad", req -> { throw HttpException.badRequest("Invalid query payload"); })
                .get("/generic", req -> { throw new RuntimeException("DB Connection failed"); })
                .build()
                .start()) {

            var client = HttpClient.newHttpClient();
            var uri = URI.create("http://127.0.0.1:" + server.localPort());

            var badResp = client.send(java.net.http.HttpRequest.newBuilder(uri.resolve("/bad")).GET().build(),
                    BodyHandlers.ofString());
            assertEquals(400, badResp.statusCode());
            assertEquals("Invalid query payload", badResp.body());

            var genericResp = client.send(java.net.http.HttpRequest.newBuilder(uri.resolve("/generic")).GET().build(),
                    BodyHandlers.ofString());
            assertEquals(500, genericResp.statusCode());
            assertEquals("Internal Server Error", genericResp.body());
        }
    }
}

