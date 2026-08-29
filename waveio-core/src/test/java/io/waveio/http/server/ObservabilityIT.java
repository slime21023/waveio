package io.waveio.http.server;

import io.waveio.http.testing.HttpServerITSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpResponse;
import io.waveio.http.routing.RouteMetadata;
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

class ObservabilityIT extends HttpServerITSupport {

    @Test
    void observerReportsExactlyOnceForSuccessMappedFailureAndTimeout() throws Exception {
        var observations = new CopyOnWriteArrayList<RequestObservation>();
        var timeoutStage = new CompletableFuture<HttpResponse>();
        var metadata = RouteMetadata.builder()
                .name("observed.route")
                .attribute("component", "test")
                .build();
        try (var observedServer = HttpServer.builder()
                .port(0)
                .handlerTimeout(Duration.ofMillis(50))
                .observe(observations::add)
                .get("/observed/:id", metadata, request -> HttpResponse.text("ok"))
                .get("/observed-failure", request -> {
                    throw new IllegalStateException("failure");
                })
                .asyncGet("/observed-timeout", request -> timeoutStage)
                .build()
                .start()) {
            var observedClient = HttpClient.newHttpClient();
            var observedBase = URI.create("http://127.0.0.1:" + observedServer.localPort());
            assertEquals(200, observedClient.send(java.net.http.HttpRequest.newBuilder(
                    observedBase.resolve("/observed/42")).GET().build(), BodyHandlers.ofString()).statusCode());
            assertEquals(500, observedClient.send(java.net.http.HttpRequest.newBuilder(
                    observedBase.resolve("/observed-failure")).GET().build(), BodyHandlers.ofString()).statusCode());
            assertEquals(504, observedClient.send(java.net.http.HttpRequest.newBuilder(
                    observedBase.resolve("/observed-timeout")).GET().build(), BodyHandlers.ofString()).statusCode());

            awaitObservationCount(observations, 3);
            assertEquals(List.of(
                            RequestObservation.Outcome.SUCCESS,
                            RequestObservation.Outcome.FAILURE,
                            RequestObservation.Outcome.TIMEOUT),
                    observations.stream().map(RequestObservation::outcome).toList());
            var success = observations.getFirst();
            assertEquals("/observed/:id", success.pathPattern().orElseThrow());
            assertEquals(metadata, success.routeMetadata());
            assertEquals(200, success.status().orElseThrow().code());
            assertTrue(success.failure().isEmpty());
            assertTrue(observations.get(1).failure().orElseThrow() instanceof IllegalStateException);
            assertTrue(observations.get(2).failure().orElseThrow()
                    instanceof java.util.concurrent.TimeoutException);
            assertTrue(timeoutStage.isCancelled());
        }
    }

    @Test
    void observerFailuresCannotAlterResponsesOrStopOtherObservers() throws Exception {
        var observations = new CopyOnWriteArrayList<RequestObservation>();
        try (var observedServer = HttpServer.builder()
                .port(0)
                .observe(observation -> { throw new IllegalStateException("observer failure"); })
                .observe(observations::add)
                .get("/still-healthy", request -> HttpResponse.text("healthy"))
                .build()
                .start()) {
            var observedClient = HttpClient.newHttpClient();
            var uri = URI.create("http://127.0.0.1:" + observedServer.localPort()
                    + "/still-healthy");

            assertEquals("healthy", observedClient.send(java.net.http.HttpRequest.newBuilder(uri)
                    .GET().build(), BodyHandlers.ofString()).body());
            assertEquals("healthy", observedClient.send(java.net.http.HttpRequest.newBuilder(uri)
                    .GET().build(), BodyHandlers.ofString()).body());

            awaitObservationCount(observations, 2);
            assertEquals(2, observations.size());
        }
    }

    @Test
    void disconnectProducesOneObservationAndCancelsAsyncStage() throws Exception {
        var observations = new CopyOnWriteArrayList<RequestObservation>();
        var invoked = new CountDownLatch(1);
        var stage = new CompletableFuture<HttpResponse>();
        try (var observedServer = HttpServer.builder()
                .port(0)
                .observe(observations::add)
                .asyncGet("/disconnect-observed", request -> {
                    invoked.countDown();
                    return stage;
                })
                .build()
                .start()) {
            var socket = new Socket("127.0.0.1", observedServer.localPort());
            socket.getOutputStream().write(("GET /disconnect-observed HTTP/1.1\r\n"
                    + "Host: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertTrue(invoked.await(2, TimeUnit.SECONDS));

            socket.close();

            awaitObservationCount(observations, 1);
            assertEquals(RequestObservation.Outcome.DISCONNECTED,
                    observations.getFirst().outcome());
            assertTrue(observations.getFirst().status().isEmpty());
            assertTrue(stage.isCancelled());
            Thread.sleep(20);
            assertEquals(1, observations.size());
        }
    }
}

