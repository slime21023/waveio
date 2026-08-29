package io.waveio.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpResponse;
import io.waveio.http.internal.body.InboundBodyPublisher;
import io.waveio.http.testing.RawHttpResponse;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StreamingRequestIT {

    @Test
    void streamsChunkedBodyAndPreservesFollowingPipelinedResponseOrder() throws Exception {
        try (var server = HttpServer.builder()
                .port(0)
                .maxBodySize(16)
                .streamingPost("/upload", (request, body) -> collect(body)
                        .thenApply(value -> HttpResponse.text("body:" + value)))
                .get("/after", request -> HttpResponse.text("after"))
                .build()
                .start();
                var socket = new Socket("127.0.0.1", server.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write((
                    "POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n"
                    + "GET /after HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));

            assertTrue(RawHttpResponse.read(socket).wireText().endsWith("body:abcde"));
            assertTrue(RawHttpResponse.read(socket).wireText().endsWith("after"));
        }
    }

    @Test
    void rejectsChunkedBodyAtLimitWithoutDeliveringOverflowingChunk() throws Exception {
        var delivered = new AtomicReference<String>();
        try (var server = HttpServer.builder()
                .port(0)
                .maxBodySize(5)
                .streamingPost("/upload", (request, body) -> collect(body).thenApply(value -> {
                    delivered.set(value);
                    return HttpResponse.text(value);
                }))
                .build()
                .start();
                var socket = new Socket("127.0.0.1", server.localPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write((
                    "POST /upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "3\r\n123\r\n3\r\n456\r\n0\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));

            assertTrue(RawHttpResponse.read(socket).wireText().startsWith("HTTP/1.1 413"));
            assertNull(delivered.get());
        }
    }

    @Test
    void disconnectFailsBodySubscriberAndCancelsHandlerStage() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var failed = new CountDownLatch(1);
        var stage = new CompletableFuture<HttpResponse>();
        try (var server = HttpServer.builder()
                .port(0)
                .handlerTimeout(Duration.ofSeconds(5))
                .streamingPost("/upload", (request, body) -> {
                    body.subscribe(new Flow.Subscriber<>() {
                        @Override public void onSubscribe(Flow.Subscription subscription) {
                            subscription.request(Long.MAX_VALUE);
                        }
                        @Override public void onNext(ByteBuffer item) {}
                        @Override public void onError(Throwable value) {
                            failure.set(value);
                            failed.countDown();
                        }
                        @Override public void onComplete() {}
                    });
                    return stage;
                })
                .build()
                .start()) {
            try (var socket = new Socket("127.0.0.1", server.localPort())) {
                socket.getOutputStream().write((
                        "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc")
                        .getBytes(StandardCharsets.US_ASCII));
            }

            assertTrue(failed.await(2, TimeUnit.SECONDS));
            assertInstanceOf(InboundBodyPublisher.DisconnectedException.class, failure.get());
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!stage.isCancelled() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(stage.isCancelled());
        }
    }

    private static CompletableFuture<String> collect(Flow.Publisher<ByteBuffer> publisher) {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }
            @Override public void onNext(ByteBuffer value) {
                var chunk = new byte[value.remaining()];
                value.get(chunk);
                bytes.writeBytes(chunk);
                subscription.request(1);
            }
            @Override public void onError(Throwable failure) {
                result.completeExceptionally(failure);
            }
            @Override public void onComplete() {
                result.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        });
        return result;
    }

}
