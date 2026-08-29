package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.routing.Router;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Transport behavior of demand-driven inbound streaming across the framing and queue stages. */
class NettyInboundStreamingTest {

    @Test
    void deliversEveryChunkDecodedFromOneReadBatch() {
        var chunks = new ArrayList<String>();
        var subscription = new AtomicReference<Flow.Subscription>();
        var result = new CompletableFuture<HttpResponse>();
        var router = Router.builder()
                .streamingPost("/upload", (request, body) -> {
                    body.subscribe(new Flow.Subscriber<>() {
                        @Override public void onSubscribe(Flow.Subscription value) {
                            subscription.set(value);
                            value.request(1);
                        }
                        @Override public void onNext(ByteBuffer value) { chunks.add(text(value)); }
                        @Override public void onError(Throwable value) {
                            result.completeExceptionally(value);
                        }
                        @Override public void onComplete() {
                            result.complete(HttpResponse.text(String.join("", chunks)));
                        }
                    });
                    return result;
                })
                .build();
        var channel = streamingChannel(router, 64);

        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                        "/upload"),
                content("one"), content("two"), content("three"),
                LastHttpContent.EMPTY_LAST_CONTENT);
        channel.runPendingTasks();

        assertTrue(channel.isOpen());
        assertEquals(List.of("one"), chunks);

        subscription.get().request(1);
        subscription.get().request(1);
        channel.runPendingTasks();

        assertEquals(List.of("one", "two", "three"), chunks);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals("onetwothree", response.content().toString(StandardCharsets.UTF_8));
        response.release();
    }

    @Test
    void completingAnEarlierPipelinedResponseKeepsAutoReadPausedForAnActiveStream() {
        var pending = new CompletableFuture<HttpResponse>();
        var router = Router.builder()
                .asyncGet("/first", request -> pending)
                .streamingPost("/upload", (request, body) -> new CompletableFuture<>())
                .build();
        var channel = streamingChannel(router, 64);

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/first"));
        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                "/upload"));
        assertFalse(channel.config().isAutoRead());

        pending.complete(HttpResponse.text("first"));
        channel.runPendingTasks();

        FullHttpResponse first = channel.readOutbound();
        assertNotNull(first);
        first.release();
        assertFalse(channel.config().isAutoRead());
    }

    @Test
    void dispatchedStreamingExchangeSuspendsTheConnectionReadDeadline() {
        var router = Router.builder()
                .streamingPost("/upload", (request, body) -> new CompletableFuture<>())
                .build();
        var idle = new ConnectionIdleHandler(Duration.ofSeconds(30), Duration.ofMillis(50));
        var channel = new EmbeddedChannel(idle, new NettyInboundRequestHandler(router, 64),
                requestHandler(router));

        channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                "/upload"));
        channel.advanceTimeBy(200, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertTrue(channel.isOpen());
    }

    @Test
    void stalledResponseBodyPublisherLosesItsConnection() {
        Flow.Publisher<ByteBuffer> stalled = subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override public void request(long amount) {}
                    @Override public void cancel() {}
                });
        var channel = stallingChannel(stalled);

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/stream"));
        channel.runPendingTasks();
        assertNotNull(channel.readOutbound()); // streaming response headers
        assertTrue(channel.isOpen());

        channel.advanceTimeBy(100, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
    }

    @Test
    void responseBodyPublisherThatNeverSubscribesLosesItsConnection() {
        Flow.Publisher<ByteBuffer> silent = subscriber -> {};
        var channel = stallingChannel(silent);

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/stream"));
        channel.runPendingTasks();
        assertNotNull(channel.readOutbound()); // streaming response headers
        assertTrue(channel.isOpen());

        channel.advanceTimeBy(100, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
    }

    @Test
    void throwingResponseBodyPublisherFailsTheWriteInsteadOfEscaping() {
        Flow.Publisher<ByteBuffer> throwing = subscriber -> {
            throw new IllegalStateException("subscribe failed");
        };
        var channel = stallingChannel(throwing);

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/stream"));
        channel.runPendingTasks();
        assertNotNull(channel.readOutbound()); // streaming response headers

        assertFalse(channel.isOpen());
    }

    @Test
    void interimContinueWaitsForAnEarlierPendingResponse() {
        var pending = new CompletableFuture<HttpResponse>();
        var router = Router.builder()
                .asyncGet("/first", request -> pending)
                .streamingPost("/upload", (request, body) -> new CompletableFuture<>())
                .build();
        var channel = streamingChannel(router, 64);

        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/first"));
        var expecting = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        expecting.headers().set("expect", "100-continue");
        channel.writeInbound(expecting);
        channel.runPendingTasks();

        assertNull(channel.readOutbound());

        pending.complete(HttpResponse.text("first"));
        channel.runPendingTasks();

        FullHttpResponse first = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, first.status());
        first.release();

        FullHttpResponse interim = channel.readOutbound();
        assertNotNull(interim);
        assertEquals(HttpResponseStatus.CONTINUE, interim.status());
        interim.release();
    }

    private static EmbeddedChannel stallingChannel(Flow.Publisher<ByteBuffer> body) {
        var router = Router.builder()
                .get("/stream", request -> HttpResponse.status(HttpStatus.OK).stream(body))
                .build();
        return new EmbeddedChannel(new NettyRequestHandler(router, List.of(),
                (failure, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Executors.newSingleThreadExecutor(), ConnectionOptions.defaults()
                        .withMaxPendingRequests(2)
                        .withResponseStallTimeout(Duration.ofMillis(50))));
    }

    private static EmbeddedChannel streamingChannel(Router router, int maxBodySize) {
        return new EmbeddedChannel(new NettyInboundRequestHandler(router, maxBodySize),
                requestHandler(router));
    }

    private static NettyRequestHandler requestHandler(Router router) {
        return new NettyRequestHandler(router, List.of(),
                (failure, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Executors.newSingleThreadExecutor(),
                ConnectionOptions.defaults().withMaxPendingRequests(4));
    }

    private static DefaultHttpContent content(String value) {
        return new DefaultHttpContent(
                Unpooled.copiedBuffer(value, StandardCharsets.UTF_8));
    }

    private static String text(ByteBuffer value) {
        var bytes = new byte[value.remaining()];
        value.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
