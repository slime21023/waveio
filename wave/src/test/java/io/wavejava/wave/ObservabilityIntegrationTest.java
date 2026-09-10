package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.api.observability.AccessLogEvent;
import io.wavejava.wave.api.observability.Observability;
import io.wavejava.wave.api.server.ServerTimeouts;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** Real-socket proof that observability ends at a transport terminal write, not middleware unwind. */
class ObservabilityIntegrationTest {
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void recordsSuccessfulAndApplicationFailedResponsesOnlyAfterTheirSocketWrites() throws Exception {
        var events = new ArrayBlockingQueue<AccessLogEvent>(8);
        var metrics = new AtomicInteger();
        var tracing = new AtomicInteger();
        var callbackThread = new AtomicReference<String>();
        var observability = Observability.builder()
                .accessLog(event -> {
                    callbackThread.set(Thread.currentThread().getName());
                    events.offer(event);
                })
                .metrics(event -> metrics.incrementAndGet())
                .tracing(event -> tracing.incrementAndGet())
                .build();
        var app = Wave.app().routes(routes -> routes
                .get("/ok", (request, response) -> response.text("ok"))
                .get("/failure", (request, response) -> {
                    throw new IllegalStateException("intentional handler failure");
                }))
                .build();

        try (var server = Wave.server(app).observability(observability).listen(0).start()) {
            var client = HttpClient.newHttpClient();
            assertEquals(200, get(client, server.port(), "/ok").statusCode());
            assertEquals(500, get(client, server.port(), "/failure").statusCode());

            var recorded = List.of(next(events), next(events));
            var success = byRoute(recorded, "/ok");
            var failure = byRoute(recorded, "/failure");
            assertEquals(200, success.status());
            assertEquals(2, success.responseBodyBytes());
            assertEquals(Outcome.Kind.SUCCESS, success.applicationOutcome());
            assertEquals(AccessLogEvent.TransportOutcome.WRITTEN, success.transportOutcome());
            assertEquals(500, failure.status());
            assertEquals(Outcome.Kind.APPLICATION_FAILURE, failure.applicationOutcome());
            assertEquals(AccessLogEvent.TransportOutcome.WRITTEN, failure.transportOutcome());
            assertFalse(callbackThread.get().contains("nioEventLoop"));
            assertTrue(metrics.get() >= 2);
            assertTrue(tracing.get() >= 2);
        }
    }

    @Test
    void recordsDeadlineAsApplicationCancellationOnlyWhenThe504WriteCompletes() throws Exception {
        var events = new ArrayBlockingQueue<AccessLogEvent>(4);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/deadline", (request, response) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            response.text("too late");
        })).build();
        var timeouts = ServerTimeouts.defaults().toBuilder()
                .requestTimeout(Duration.ofMillis(125))
                .readTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(5))
                .idleTimeout(Duration.ofSeconds(5))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();

        try (var server = Wave.server(app)
                .observability(Observability.builder().accessLog(events::offer).build())
                .timeouts(timeouts)
                .listen(0)
                .start()) {
            var response = get(HttpClient.newHttpClient(), server.port(), "/deadline");
            assertEquals(504, response.statusCode());
            assertTrue(started.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(interrupted.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            var event = next(events);
            assertEquals("/deadline", event.route());
            assertEquals(504, event.status());
            assertEquals(Outcome.Kind.DEADLINE_EXCEEDED, event.applicationOutcome());
            assertEquals(AccessLogEvent.TransportOutcome.WRITTEN, event.transportOutcome());
        }
    }

    @Test
    void countsOnlySuccessfullyWrittenStreamingBodyItemsAtTerminalChunkCompletion() throws Exception {
        var events = new ArrayBlockingQueue<AccessLogEvent>(4);
        var app = Wave.app().routes(routes -> routes.get("/stream", (request, response) ->
                response.stream(fixedStream("stream".getBytes(StandardCharsets.UTF_8))))).build();

        try (var server = Wave.server(app)
                .observability(Observability.builder().accessLog(events::offer).build())
                .listen(0)
                .start()) {
            var response = get(HttpClient.newHttpClient(), server.port(), "/stream");
            assertEquals(200, response.statusCode());
            assertEquals("stream", response.body());

            var event = next(events);
            assertEquals("/stream", event.route());
            assertEquals(6, event.responseBodyBytes());
            assertEquals(AccessLogEvent.TransportOutcome.WRITTEN, event.transportOutcome());
        }
    }

    @Test
    void recordsClientDisconnectWithoutPretendingAnUnwrittenResponseSucceeded() throws Exception {
        var events = new ArrayBlockingQueue<AccessLogEvent>(4);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/disconnect", (request, response) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            response.text("unreachable");
        })).build();

        try (var server = Wave.server(app)
                .observability(Observability.builder().accessLog(events::offer).build())
                .listen(0)
                .start();
             var socket = new Socket("127.0.0.1", server.port())) {
            var wire = "GET /disconnect HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(started.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            socket.close();
            assertTrue(interrupted.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            var event = next(events);
            assertEquals("/disconnect", event.route());
            assertEquals(0, event.status());
            assertEquals(Outcome.Kind.CLIENT_CANCELLATION, event.applicationOutcome());
            assertEquals(AccessLogEvent.TransportOutcome.CANCELLED, event.transportOutcome());
        }
    }

    @Test
    void recordsServerShutdownAsCancellationAndDrainsTheBoundedObserverBeforeCloseReturns() throws Exception {
        var events = new ArrayBlockingQueue<AccessLogEvent>(4);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var app = Wave.app().routes(routes -> routes.get("/shutdown", (request, response) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            response.text("unreachable");
        })).build();
        var server = Wave.server(app)
                .observability(Observability.builder().accessLog(events::offer).build())
                .listen(0)
                .start();
        try (var socket = new Socket("127.0.0.1", server.port())) {
            var wire = "GET /shutdown HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n";
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(started.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            server.close();
            assertTrue(interrupted.await(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            var event = next(events);
            assertEquals("/shutdown", event.route());
            assertEquals(Outcome.Kind.CLIENT_CANCELLATION, event.applicationOutcome());
            assertEquals(AccessLogEvent.TransportOutcome.CANCELLED, event.transportOutcome());
        } finally {
            server.close();
        }
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static AccessLogEvent byRoute(List<AccessLogEvent> events, String route) {
        return events.stream().filter(event -> event.route().equals(route)).findFirst().orElseThrow();
    }

    private static AccessLogEvent next(ArrayBlockingQueue<AccessLogEvent> events) throws InterruptedException {
        var event = events.poll(ASSERTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (event == null) {
            throw new AssertionError("timed out waiting for observability event");
        }
        return event;
    }

    private static Flow.Publisher<ByteBuffer> fixedStream(byte[] bytes) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean delivered;
            private boolean cancelled;

            @Override
            public void request(long count) {
                if (cancelled || delivered) {
                    return;
                }
                if (count <= 0) {
                    delivered = true;
                    subscriber.onError(new IllegalArgumentException("Flow demand must be positive"));
                    return;
                }
                delivered = true;
                subscriber.onNext(ByteBuffer.wrap(bytes));
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        });
    }
}
