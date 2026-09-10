package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.websocket.WebSocketLimits;
import io.wavejava.wave.runtime.InvocationRuntime;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Deterministic transport-budget tests for one already upgraded WebSocket pipeline. */
class WebSocketSessionHandlerTest {
    @Test
    void mandatoryPongAndCloseUseReservedControlCapacityWhenApplicationBudgetIsFull() {
        var heldWrites = WebSocketTransportFixture.hold(message -> message instanceof TextWebSocketFrame);
        var limits = new WebSocketLimits(64, 64, 80, Duration.ofSeconds(1));
        var runtime = new InvocationRuntime();
        var handler = new WebSocketSessionHandler(Request.of(HttpMethod.GET, "/control"), limits, runtime);
        var channel = new EmbeddedChannel(heldWrites, handler);
        try {
            activate(channel, handler);
            CompletionStage<Void> applicationWrite = handler.sendText("x".repeat(64));
            channel.runPendingTasks();
            assertTrue(heldWrites.isHolding(), "the application byte reservation must remain pending");
            assertFalse(applicationWrite.toCompletableFuture().isDone());

            channel.writeInbound(new PingWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {7})));
            channel.runPendingTasks();
            var pong = assertInstanceOf(PongWebSocketFrame.class, channel.readOutbound());
            try {
                assertTrue(pong.content().isReadable(), "mandatory pong must bypass a full application budget");
            } finally {
                pong.release();
            }

            channel.writeInbound(new CloseWebSocketFrame(1000, "done"));
            channel.runPendingTasks();
            var close = assertInstanceOf(CloseWebSocketFrame.class, channel.readOutbound());
            try {
                assertTrue(close.statusCode() == 1000,
                        "mandatory close acknowledgement must bypass a full application budget");
            } finally {
                close.release();
            }
        } finally {
            heldWrites.failPending();
            channel.finishAndReleaseAll();
            runtime.close();
        }
    }

    @Test
    void mandatoryPongUsesItsOwnReserveWhenApplicationControlWritesAreSaturated() {
        var heldWrites = WebSocketTransportFixture.hold(message -> message instanceof PingWebSocketFrame);
        var limits = new WebSocketLimits(125, 125, 160, Duration.ofSeconds(1));
        var runtime = new InvocationRuntime();
        var handler = new WebSocketSessionHandler(Request.of(HttpMethod.GET, "/control"), limits, runtime);
        var channel = new EmbeddedChannel(heldWrites, handler);
        try {
            activate(channel, handler);
            // Hold exactly 512 wire-accounted bytes of application Ping traffic (three 139-byte
            // frames and one 95-byte frame) so no capacity remains in that separate budget.
            for (var payloadBytes : new int[] {125, 125, 125, 81}) {
                handler.ping(new byte[payloadBytes]);
                channel.runPendingTasks();
            }
            assertTrue(heldWrites.isHolding(), "application control writes should remain blocked by the slow peer");

            channel.writeInbound(new PingWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {9})));
            channel.runPendingTasks();
            var pong = assertInstanceOf(PongWebSocketFrame.class, channel.readOutbound());
            try {
                assertTrue(pong.content().isReadable(),
                        "transport Ping must receive Pong despite saturated application control writes");
            } finally {
                pong.release();
            }
        } finally {
            heldWrites.failPending();
            channel.finishAndReleaseAll();
            runtime.close();
        }
    }

    @Test
    void sendsApplicationMessagesThenCompletesThePeerCloseHandshake() throws Exception {
        var runtime = new InvocationRuntime();
        var handler = new WebSocketSessionHandler(Request.of(HttpMethod.GET, "/chat"),
                new WebSocketLimits(256, 256, 512, Duration.ofSeconds(1)), runtime);
        var channel = new EmbeddedChannel(handler);
        try {
            activate(channel, handler);
            var write = handler.sendText("hello");
            channel.runPendingTasks();
            assertTrue(write.toCompletableFuture().get(1, TimeUnit.SECONDS) == null);
            var text = assertInstanceOf(TextWebSocketFrame.class, channel.readOutbound());
            try {
                assertTrue(text.text().equals("hello"));
            } finally {
                text.release();
            }
            assertTrue(handler.isOpen());

            channel.writeInbound(new CloseWebSocketFrame(1000, "done"));
            channel.runPendingTasks();
            var acknowledgement = assertInstanceOf(CloseWebSocketFrame.class, channel.readOutbound());
            try {
                assertTrue(acknowledgement.statusCode() == 1000);
            } finally {
                acknowledgement.release();
            }
            handler.closed().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertFalse(handler.isOpen());
        } finally {
            channel.finishAndReleaseAll();
            runtime.close();
        }
    }

    @Test
    void rejectsFramesReceivedBeforeTheOrderedHandshakeActivation() {
        var runtime = new InvocationRuntime();
        var handler = new WebSocketSessionHandler(Request.of(HttpMethod.GET, "/chat"),
                new WebSocketLimits(256, 256, 512, Duration.ofSeconds(1)), runtime);
        var channel = new EmbeddedChannel(handler);
        try {
            channel.writeInbound(new TextWebSocketFrame("too early"));
            channel.runPendingTasks();
            assertFalse(channel.isActive());
            assertFalse(handler.isOpen());
        } finally {
            channel.finishAndReleaseAll();
            runtime.close();
        }
    }

    /** Makes an embedded handler represent a completed HTTP-to-WebSocket upgrade. */
    private static void activate(EmbeddedChannel channel, WebSocketSessionHandler handler) {
        handler.armHandshakeReads();
        handler.activateAfterHandshake();
        channel.runPendingTasks();
    }

}
