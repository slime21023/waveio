package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.routing.Router;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class NettyRequestHandlerTest {

    @Test
    void malformedPipelinedRequestWaitsForEarlierResponse() {
        var first = new CompletableFuture<HttpResponse>();
        var router = Router.builder()
                .asyncGet("/first", request -> first)
                .build();
        var handler = new NettyRequestHandler(router, List.of(),
                (error, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Executors.newSingleThreadExecutor());
        var channel = new EmbeddedChannel(handler);

        channel.writeInbound(request("/first"));
        var malformed = request("/bad");
        malformed.setDecoderResult(DecoderResult.failure(
                new IllegalArgumentException("Decoder failure")));
        channel.writeInbound(malformed);
        channel.runPendingTasks();

        assertNull(channel.readOutbound());
        first.complete(HttpResponse.text("first"));
        channel.runPendingTasks();

        FullHttpResponse firstResponse = channel.readOutbound();
        FullHttpResponse rejectedResponse = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, firstResponse.status());
        assertEquals(HttpResponseStatus.BAD_REQUEST, rejectedResponse.status());
        firstResponse.release();
        rejectedResponse.release();
    }

    @Test
    void handlesMalformedRequestWith400() {
        var router = Router.builder().get("/test", req -> HttpResponse.noContent()).build();
        var handler = new NettyRequestHandler(router, List.of(), (err, req) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Executors.newSingleThreadExecutor());

        var channel = new EmbeddedChannel(handler);

        var nettyReq = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        nettyReq.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("Decoder failure")));

        channel.writeInbound(nettyReq);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        response.release();
    }

    @Test
    void exceptionCaughtClosesChannel() {
        var router = Router.builder().build();
        var handler = new NettyRequestHandler(router, List.of(), (err, req) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Executors.newSingleThreadExecutor());

        var channel = new EmbeddedChannel(handler);
        assertTrue(channel.isOpen());

        channel.pipeline().fireExceptionCaught(new RuntimeException("Connection error"));
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
    }

    @Test
    void processesPipelinedRequestsInOrder() {
        var router = Router.builder()
                .get("/first", req -> HttpResponse.text("1"))
                .get("/second", req -> HttpResponse.text("2"))
                .build();
        var handler = new NettyRequestHandler(router, List.of(), (err, req) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Executors.newSingleThreadExecutor());

        var channel = new EmbeddedChannel(handler);

        var req1 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/first");
        var req2 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/second");

        channel.writeInbound(req1);
        channel.writeInbound(req2);
        channel.runPendingTasks();

        FullHttpResponse resp1 = channel.readOutbound();
        FullHttpResponse resp2 = channel.readOutbound();

        assertNotNull(resp1);
        assertNotNull(resp2);
        assertEquals(HttpResponseStatus.OK, resp1.status());
        assertEquals(HttpResponseStatus.OK, resp2.status());
        resp1.release();
        resp2.release();
    }

    @Test
    void closesConnectionWhenAlreadyBufferedRequestsExceedConfiguredBound() {
        var executor = new QueuedExecutor();
        var invoked = new java.util.concurrent.atomic.AtomicInteger();
        var router = Router.builder()
                .blockingGet("/work", req -> {
                    invoked.incrementAndGet();
                    return HttpResponse.text("done");
                })
                .build();
        var handler = new NettyRequestHandler(router, List.of(),
                (err, req) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                executor, ConnectionOptions.defaults().withMaxPendingRequests(2));
        var channel = new EmbeddedChannel(handler);

        channel.writeInbound(request("/work")); // active
        channel.writeInbound(request("/work")); // pending 1
        channel.writeInbound(request("/work")); // pending 2, auto-read paused
        assertFalse(channel.config().isAutoRead());

        channel.writeInbound(request("/work")); // simulates an already-decoded batch overflow
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        executor.runAll();
        channel.runPendingTasks();
        assertEquals(0, invoked.get());
        assertEquals(0, executor.queuedTasks());
    }

    @Test
    void channelCloseCancelsStreamingSubscriptionAndDropsQueuedRequests() {
        var cancelled = new AtomicBoolean();
        var queuedInvocations = new java.util.concurrent.atomic.AtomicInteger();
        Flow.Publisher<ByteBuffer> publisher = subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override public void request(long amount) {}
                    @Override public void cancel() { cancelled.set(true); }
                });
        var router = Router.builder()
                .get("/stream", req -> HttpResponse.status(HttpStatus.OK).stream(publisher))
                .get("/queued", req -> {
                    queuedInvocations.incrementAndGet();
                    return HttpResponse.noContent();
                })
                .build();
        var handler = new NettyRequestHandler(router, List.of(),
                (err, req) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Executors.newSingleThreadExecutor(),
                ConnectionOptions.defaults().withMaxPendingRequests(2));
        var channel = new EmbeddedChannel(handler);

        channel.writeInbound(request("/stream"));
        channel.writeInbound(request("/queued"));
        assertNotNull(channel.readOutbound()); // streaming response headers

        channel.close();
        channel.runPendingTasks();

        assertTrue(cancelled.get());
        assertEquals(0, queuedInvocations.get());
    }

    @Test
    void timeoutUsesExceptionMappingAndLateCompletionCannotWriteTwice() {
        var executor = new QueuedExecutor();
        var mappedTimeout = new AtomicBoolean();
        var router = Router.builder()
                .blockingGet("/slow", req -> HttpResponse.text("late"))
                .get("/next", req -> HttpResponse.text("next"))
                .build();
        var handler = new NettyRequestHandler(router, List.of(), (failure, request) -> {
            mappedTimeout.set(failure instanceof java.util.concurrent.TimeoutException);
            return HttpResponse.status(HttpStatus.of(598, "Mapped Timeout")).body("timeout");
        }, executor, ConnectionOptions.defaults().withMaxPendingRequests(2)
                .withHandlerTimeout(Duration.ofMillis(10)));
        var channel = new EmbeddedChannel(handler);

        channel.writeInbound(request("/slow"));
        channel.writeInbound(request("/next"));
        channel.advanceTimeBy(11, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        FullHttpResponse timeout = channel.readOutbound();
        FullHttpResponse next = channel.readOutbound();
        assertTrue(mappedTimeout.get());
        assertEquals(598, timeout.status().code());
        assertEquals(HttpResponseStatus.OK, next.status());
        timeout.release();
        next.release();

        executor.runAll();
        channel.runPendingTasks();
        assertNull(channel.readOutbound());
    }

    @Test
    void eventLoopHandlerResultIsRejectedWhenItReturnsAfterDeadline() {
        var router = Router.builder()
                .get("/event-loop-slow", req -> {
                    Thread.sleep(30);
                    return HttpResponse.text("late");
                })
                .build();
        var handler = new NettyRequestHandler(router, List.of(),
                (failure, request) -> HttpResponse.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body("timeout"),
                Executors.newSingleThreadExecutor(), ConnectionOptions.defaults()
                        .withMaxPendingRequests(2).withHandlerTimeout(Duration.ofMillis(1)));
        var channel = new EmbeddedChannel(handler);

        channel.writeInbound(request("/event-loop-slow"));
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(504, response.status().code());
        response.release();
    }

    private static DefaultFullHttpRequest request(String path) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) { tasks.addLast(command); }
        void runAll() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
        int queuedTasks() { return tasks.size(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; var result = List.copyOf(tasks); tasks.clear(); return result; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
