package io.waveio.http.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.AttributeKey;
import io.waveio.http.Cookie;
import io.waveio.http.HttpException;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.server.HttpServer;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public abstract class HttpServerITSupport {
    protected static final AttributeKey<String> SOURCE = AttributeKey.of("source");

    protected HttpServer server;
    protected HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    protected URI baseUri;

    @TempDir
    protected Path tempDirectory;

    protected void startServer() {
        Path responseFile;
        try {
            responseFile = tempDirectory.resolve("response.txt");
            Files.writeString(responseFile, "file-body-" + "x".repeat(40_000));
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        server = HttpServer.builder()
                .port(0)
                .maxBodySize(8)
                .readTimeout(Duration.ofSeconds(5))
                .use((request, chain) -> {
                    request.setAttribute(SOURCE, "middleware");
                    return chain.next(request);
                })
                .get("/hello", request -> HttpResponse.text("hello"))
                .get("/café/:item", request -> HttpResponse.text(
                        request.requirePathParam("item") + ":"
                                + String.join(":", request.queryParams("tag"))))
                .get("/head-fallback", request -> HttpResponse.text("representation"))
                .head("/head-explicit", request -> HttpResponse.status(HttpStatus.OK)
                        .header("x-handler", "head")
                        .body("explicit"))
                .get("/head-explicit", request -> HttpResponse.status(HttpStatus.OK)
                        .header("x-handler", "get")
                        .body("fallback"))
                .get("/no-content-body", request -> HttpResponse.status(HttpStatus.NO_CONTENT)
                        .header("content-length", "999")
                        .header("transfer-encoding", "chunked")
                        .body("must-not-appear"))
                .get("/not-modified-body", request -> HttpResponse.status(HttpStatus.of(304, "Not Modified"))
                        .header("content-length", "999")
                        .header("transfer-encoding", "chunked")
                        .body("must-not-appear"))
                .get("/early-hints-body", request -> HttpResponse.status(HttpStatus.of(103, "Early Hints"))
                        .header("content-length", "999")
                        .header("transfer-encoding", "chunked")
                        .body("must-not-appear"))
                .get("/users/:id", request -> HttpResponse.text(
                        request.pathParam("id").orElseThrow() + ":"
                                + request.queryParam("view").orElse("default") + ":"
                                + request.attribute(SOURCE).orElseThrow()))
                .post("/echo", request -> HttpResponse.text(request.body().text()))
                .blockingGet("/thread", request -> HttpResponse.text(
                        Boolean.toString(Thread.currentThread().isVirtual())))
                .blockingGet("/slow", request -> {
                    Thread.sleep(100);
                    return HttpResponse.text("slow");
                })
                .get("/stream", request -> HttpResponse.status(HttpStatus.OK)
                        .header("content-type", "text/plain")
                        .stream(TestPublishers.strings("alpha", "-", "beta")))
                .get("/file", request -> HttpResponse.status(HttpStatus.OK)
                        .header("content-type", "text/plain")
                        .file(responseFile))
                .get("/cookie", request -> HttpResponse.status(HttpStatus.OK)
                        .cookie(Cookie.builder("session", "renewed")
                                .path("/")
                                .httpOnly(true)
                                .secure(true)
                                .sameSite(Cookie.SameSite.LAX)
                                .build())
                        .body(request.cookie("session").orElse("missing")))
                .get("/missing-user", request -> {
                    throw HttpException.notFound("User not found");
                })
                .get("/failure", request -> {
                    throw new IllegalStateException("secret detail");
                })
                .build()
                .start();
        baseUri = URI.create("http://127.0.0.1:" + server.localPort());
    }

    protected void stopServer() {
        if (server != null) server.close();
    }

    protected java.net.http.HttpResponse<String> get(String path) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(baseUri.resolve(path)).GET().build();
        return client.send(request, BodyHandlers.ofString());
    }

    protected static String readOneResponse(Socket socket) throws Exception {
        return RawHttpResponse.read(socket).wireText();
    }

    protected static String readResponseHeaders(Socket socket) throws Exception {
        return RawHttpResponse.readWireHeaders(socket);
    }

    protected static void awaitRunning(HttpServer server) throws InterruptedException {
        Await.until(server::isRunning, Duration.ofSeconds(5), "server did not start in time");
    }

    protected static void awaitObservationCount(List<?> observations, int expected)
            throws InterruptedException {
        Await.until(() -> observations.size() >= expected, Duration.ofSeconds(2),
                "observation count did not reach " + expected);
        assertEquals(expected, observations.size());
    }

    protected static Middleware recordingMiddleware(List<String> order, String name) {
        return (request, chain) -> {
            order.add(name + "-before");
            var response = chain.next(request);
            order.add(name + "-after");
            return response;
        };
    }
}
