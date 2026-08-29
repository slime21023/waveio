package io.waveio.http.server;

import io.waveio.http.testing.DefaultHttpServerITSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpResponse;
import io.waveio.http.routing.Router;
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

class ServerLifecycleIT extends DefaultHttpServerITSupport {

    @Test
    void exposesLifecycleState() {
        assertTrue(server.isRunning());
        assertTrue(server.localPort() > 0);

        server.stop();

        assertFalse(server.isRunning());
    }

    @Test
    void awaitTerminationReturnsAfterStop() throws Exception {
        var waiter = Thread.startVirtualThread(server::awaitTerminationUninterruptibly);

        server.stop();
        waiter.join(5_000);

        assertFalse(waiter.isAlive());
    }

    @Test
    void cannotAwaitServerThatHasNotStarted() {
        try (var unstarted = HttpServer.builder().port(0).build()) {
            assertThrows(IllegalStateException.class, unstarted::awaitTerminationUninterruptibly);
        }
    }

    @Test
    void runUsesExternalRouterAndOwnsLifecycle() throws Exception {
        var router = Router.builder()
                .group("/composed", routes -> routes
                        .get("", request -> HttpResponse.text(
                                request.attribute(SOURCE).orElseThrow()))
                        .use((request, chain) -> {
                            request.setAttribute(SOURCE, "composed");
                            return chain.next(request);
                        }))
                .build();
        var runServer = HttpServer.builder().port(0).router(router).build();
        var runner = Thread.startVirtualThread(runServer::run);
        awaitRunning(runServer);

        var request = java.net.http.HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + runServer.localPort() + "/composed"))
                .GET()
                .build();
        assertEquals("composed", client.send(request, BodyHandlers.ofString()).body());

        runServer.stop();
        runner.join(5_000);
        assertFalse(runner.isAlive());
        assertFalse(runServer.isRunning());
    }

    @Test
    void rejectsMixingExternalRouterAndInlineRoutes() {
        var router = Router.builder().build();

        assertThrows(IllegalStateException.class, () -> HttpServer.builder()
                .router(router)
                .get("/inline", request -> HttpResponse.noContent()));
        assertThrows(IllegalStateException.class, () -> HttpServer.builder()
                .get("/inline", request -> HttpResponse.noContent())
                .router(router));
    }

    @Test
    void interruptingRunPreservesInterruptAndClosesServer() throws Exception {
        var runServer = HttpServer.builder().port(0).build();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var runner = Thread.startVirtualThread(() -> {
            runServer.run();
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        awaitRunning(runServer);

        runner.interrupt();
        runner.join(5_000);

        assertTrue(interrupted.get());
        assertFalse(runServer.isRunning());
    }

    @Test
    void shutdownClosesActiveConnections() throws Exception {
        try (var socket = new Socket("127.0.0.1", server.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                    "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            readOneResponse(socket);

            server.stop();

            assertEquals(-1, socket.getInputStream().read());
        }
    }
}
