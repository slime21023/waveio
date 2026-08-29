package io.waveio.http.middleware;

import io.waveio.http.testing.HttpServerITSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpResponse;
import io.waveio.http.routing.Router;
import io.waveio.http.server.HttpServer;
import io.waveio.http.testing.Await;
import io.waveio.http.testing.RawHttpResponse;
import io.waveio.http.testing.TestPublishers;
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

class MiddlewareIT extends HttpServerITSupport {

    @Test
    void composesMiddlewareInRegistrationOrder() throws Exception {
        var order = new ArrayList<String>();
        try (var middlewareServer = HttpServer.builder()
                .port(0)
                .use((request, chain) -> {
                    order.add("first-before");
                    var response = chain.next(request);
                    order.add("first-after");
                    return response;
                })
                .use((request, chain) -> {
                    order.add("second-before");
                    var response = chain.next(request);
                    order.add("second-after");
                    return response;
                })
                .get("/", request -> {
                    order.add("handler");
                    return HttpResponse.noContent();
                })
                .build()
                .start()) {
            var request = java.net.http.HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + middlewareServer.localPort()))
                    .GET()
                    .build();

            assertEquals(204, client.send(request, BodyHandlers.discarding()).statusCode());
            assertEquals(List.of("first-before", "second-before", "handler",
                    "second-after", "first-after"), order);
        }
    }

    @Test
    void nestsGlobalAndGroupedMiddlewareByScopeNotDeclarationPosition() throws Exception {
        var order = new ArrayList<String>();
        Middleware root = recordingMiddleware(order, "router");
        Middleware parent = recordingMiddleware(order, "parent");
        Middleware child = recordingMiddleware(order, "child");
        var router = Router.builder()
                .group("/api", api -> {
                    api.group("/admin", admin -> admin
                            .get("/users", request -> {
                                order.add("handler");
                                return HttpResponse.noContent();
                            })
                            .use(child));
                    api.use(parent);
                })
                .use(root)
                .build();

        try (var groupedServer = HttpServer.builder()
                .port(0)
                .router(router)
                .use(recordingMiddleware(order, "global"))
                .build()
                .start()) {
            var request = java.net.http.HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + groupedServer.localPort() + "/api/admin/users"))
                    .GET()
                    .build();

            assertEquals(204, client.send(request, BodyHandlers.discarding()).statusCode());
            assertEquals(List.of("global-before", "router-before", "parent-before",
                    "child-before", "handler", "child-after", "parent-after",
                    "router-after", "global-after"), order);
        }
    }
}

