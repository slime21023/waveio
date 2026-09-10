package io.wavejava.wave.netty;

import io.wavejava.wave.api.websocket.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Request;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Event-loop transport bridge for one direct client WebSocket after a validated upgrade. */
final class WebSocketClientSession extends SimpleChannelInboundHandler<WebSocketFrame> implements WebSocketConnection {
    private static final int NORMAL_CLOSE = 1000;
    private static final int PROTOCOL_ERROR = 1002;
    private static final int MESSAGE_TOO_LARGE = 1009;
    private static final int INTERNAL_ERROR = 1011;
    private static final long CONTROL_PLANE_MAXIMUM_BYTES = 512;
    private static final long CLOSE_PLANE_MAXIMUM_BYTES = 139; // 123-byte reason + code + masked frame header.

    private final URI uri;
    private final String subprotocol;
    private final WebSocketLimits limits;
    private final CancellationToken sessionCancellation = CancellationToken.create();
    private final Request request;
    private final Budget outboundBudget;
    private final Budget applicationControlBudget = new Budget(CONTROL_PLANE_MAXIMUM_BYTES);
    private final Budget mandatoryControlBudget = new Budget(CONTROL_PLANE_MAXIMUM_BYTES);
    private final Budget closeBudget = new Budget(CLOSE_PLANE_MAXIMUM_BYTES);
    private final WebSocketClientInboundPublisher inbound;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Object pendingWriteLock = new Object();
    private final Map<CompletableFuture<Void>, PendingWrite> pendingWrites = new HashMap<>();
    private final AtomicBoolean transportTerminationStarted = new AtomicBoolean();

    private volatile ChannelHandlerContext context;
    private volatile boolean activated;
    private volatile boolean closing;
    private boolean transportClosed;
    private boolean peerCloseReceived;
    private boolean awaitingPong;
    private io.netty.util.concurrent.ScheduledFuture<?> closeDeadline;

    WebSocketClientSession(
            WebSocketClientRequest clientRequest,
            URI uri,
            String subprotocol,
            WebSocketLimits limits,
            Executor callbackExecutor) {
        var sourceRequest = Objects.requireNonNull(clientRequest, "clientRequest");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.subprotocol = subprotocol;
        this.limits = Objects.requireNonNull(limits, "limits");
        request = WebSocketRequestAdapter.toRequest(sourceRequest, sessionCancellation);
        outboundBudget = new Budget(limits.maximumOutboundBytes());
        inbound = new WebSocketClientInboundPublisher(Objects.requireNonNull(callbackExecutor, "callbackExecutor"),
                new WebSocketClientInboundPublisher.Listener() {
                    @Override
                    public void onFailure(String reason, Throwable cause) {
                        runOnEventLoop(() -> failSession(reason, cause, INTERNAL_ERROR));
                    }

                    @Override
                    public void onTerminalDeliveryRejected(Throwable cause) {
                        runOnEventLoop(() -> abortAfterTerminalDeliveryRejection(cause));
                    }
                });
    }

    /** Makes the previously installed session eligible for normal application traffic. */
    void activate() {
        activated = true;
    }

    @Override
    public Request request() {
        return request;
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public Optional<String> subprotocol() {
        return Optional.ofNullable(subprotocol);
    }

    @Override
    public java.util.concurrent.Flow.Publisher<WebSocketMessage> inbound() {
        return inbound;
    }

    @Override
    public CompletionStage<Void> send(WebSocketMessage message) {
        var outbound = Objects.requireNonNull(message, "message");
        if (outbound.type() == WebSocketMessage.Type.CLOSE) {
            return close(outbound.closeCode().orElseThrow(), outbound.closeReason().orElseThrow());
        }
        try {
            validateOutbound(outbound);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return switch (outbound.type()) {
            case TEXT, BINARY -> reserveAndDispatch(
                    outbound,
                    outboundBudget,
                    "WebSocket outbound byte budget is exhausted",
                    true);
            case PING, PONG -> reserveAndDispatch(
                    outbound,
                    applicationControlBudget,
                    "WebSocket control-plane byte budget is exhausted",
                    true);
            case CLOSE -> throw new AssertionError("close was handled before outbound dispatch");
        };
    }

    @Override
    public CompletionStage<Void> close(int code, String reason) {
        var close = WebSocketMessage.close(code, reason);
        if (!executeOnEventLoop(() -> beginClose(
                close.closeCode().orElseThrow(), close.closeReason().orElseThrow(), false))) {
            var failure = new WebSocketClosedException("WebSocket event loop is no longer available");
            sessionCancellation.cancel(failure.getMessage());
            closed.completeExceptionally(failure);
        }
        return closed;
    }

    @Override
    public CompletionStage<Void> closed() {
        return closed;
    }

    @Override
    public boolean isOpen() {
        var activeContext = context;
        return activated && activeContext != null && activeContext.channel().isActive()
                && !closing && !isTransportClosed();
    }

    @Override
    public void abort() {
        sessionCancellation.cancel("WebSocket connection aborted");
        if (!executeOnEventLoop(() -> context.close())) {
            closed.completeExceptionally(new WebSocketClosedException("WebSocket event loop is no longer available"));
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        this.context = context;
        context.channel().closeFuture().addListener(ignored -> terminateTransport(context));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
        if (!activated) {
            context.close();
            return;
        }
        if (frame instanceof TextWebSocketFrame text) {
            if (!closing) {
                offerInbound(WebSocketMessage.text(text.text()));
            }
            return;
        }
        if (frame instanceof BinaryWebSocketFrame binary) {
            if (!closing) {
                offerInbound(WebSocketMessage.binary(copy(binary.content())));
            }
            return;
        }
        if (frame instanceof PingWebSocketFrame ping) {
            writeMandatoryControl(WebSocketMessage.pong(copy(ping.content())), false);
            return;
        }
        if (frame instanceof PongWebSocketFrame) {
            awaitingPong = false;
            return;
        }
        if (frame instanceof CloseWebSocketFrame close) {
            final WebSocketMessage peerClose;
            try {
                var code = close.statusCode() < 0 ? NORMAL_CLOSE : close.statusCode();
                peerClose = WebSocketMessage.close(code, close.reasonText());
            } catch (IllegalArgumentException malformedClose) {
                failSession("invalid WebSocket close frame", malformedClose, PROTOCOL_ERROR);
                return;
            }
            peerCloseReceived = true;
            inbound.completeAfterPeerClose(peerClose);
            if (closing) {
                cancelCloseDeadline();
                context.close();
            } else {
                beginClose(peerClose.closeCode().orElseThrow(), peerClose.closeReason().orElseThrow(), true);
            }
            return;
        }
        failSession("unsupported WebSocket frame", null, PROTOCOL_ERROR);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof ChannelInputShutdownEvent || event instanceof ChannelInputShutdownReadComplete) {
            terminateTransport(context);
            context.close();
            return;
        }
        if (event instanceof IdleStateEvent) {
            if (closing) {
                return;
            }
            if (awaitingPong) {
                beginClose(NORMAL_CLOSE, "idle timeout", false);
            } else {
                awaitingPong = true;
                writeMandatoryControl(WebSocketMessage.ping(new byte[0]), false);
            }
            return;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        if (cause instanceof TooLongFrameException) {
            failSession("WebSocket frame or message exceeded its configured limit", cause, MESSAGE_TOO_LARGE);
            return;
        }
        if (cause instanceof CorruptedFrameException) {
            failSession("WebSocket peer sent an invalid frame", cause, PROTOCOL_ERROR);
            return;
        }
        failSession("WebSocket transport failure", cause, INTERNAL_ERROR);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        terminateTransport(context);
        context.fireChannelInactive();
    }

    private void offerInbound(WebSocketMessage message) {
        if (!inbound.offer(message)) {
            beginClose(MESSAGE_TOO_LARGE, "inbound consumer is too slow", false);
        }
    }

    private CompletionStage<Void> reserveAndDispatch(
            WebSocketMessage message,
            Budget budget,
            String exhaustedMessage,
            boolean closeOnBudgetExhaustion) {
        var completion = new CompletableFuture<Void>();
        var pending = new PendingWrite(budget, estimatedWireBytes(message));
        Throwable failure = null;
        var exhausted = false;
        synchronized (pendingWriteLock) {
            if (transportClosed || !isOpen()) {
                failure = new WebSocketClosedException("WebSocket session is closed");
            } else if (!budget.tryReserve(pending.wireBytes())) {
                failure = new WebSocketLimitExceededException(exhaustedMessage);
                exhausted = true;
            } else {
                pendingWrites.put(completion, pending);
                if (!executeOnEventLoop(() -> writeApplicationMessage(message, completion))) {
                    pendingWrites.remove(completion);
                    budget.release(pending.wireBytes());
                    failure = new WebSocketClosedException("WebSocket event loop is no longer available");
                }
            }
        }
        if (failure != null) {
            completion.completeExceptionally(failure);
            if (exhausted && closeOnBudgetExhaustion) {
                runOnEventLoop(() -> beginClose(MESSAGE_TOO_LARGE, "outbound budget exhausted", false));
            }
        }
        return completion;
    }

    private void writeApplicationMessage(WebSocketMessage message, CompletableFuture<Void> completion) {
        if (!isOpen()) {
            releasePendingWrite(completion, new WebSocketClosedException("WebSocket session is closed"));
            return;
        }
        final WebSocketFrame frame;
        try {
            frame = toFrame(message);
        } catch (RuntimeException failure) {
            releasePendingWrite(completion, failure);
            return;
        }
        final ChannelFuture write;
        try {
            write = context.writeAndFlush(frame);
        } catch (RuntimeException failure) {
            releasePendingWrite(completion, failure);
            beginClose(INTERNAL_ERROR, "write setup failed", false);
            return;
        }
        write.addListener(future -> {
            if (future.isSuccess()) {
                releasePendingWrite(completion, null);
            } else {
                var failure = future.cause() == null
                        ? new WebSocketClosedException("WebSocket write failed")
                        : future.cause();
                releasePendingWrite(completion, failure);
                beginClose(INTERNAL_ERROR, "write failed", false);
            }
        });
    }

    private void writeMandatoryControl(WebSocketMessage message, boolean closeAfterWrite) {
        var activeContext = context;
        if (activeContext == null || !activeContext.channel().isActive()) {
            return;
        }
        var budget = message.type() == WebSocketMessage.Type.CLOSE ? closeBudget : mandatoryControlBudget;
        var wireBytes = estimatedWireBytes(message);
        if (!budget.tryReserve(wireBytes)) {
            if (message.type() == WebSocketMessage.Type.CLOSE) {
                activeContext.close();
            } else {
                beginClose(INTERNAL_ERROR, "control plane exhausted", false);
            }
            return;
        }
        final WebSocketFrame frame;
        try {
            frame = toFrame(message);
        } catch (RuntimeException failure) {
            budget.release(wireBytes);
            failSession("invalid WebSocket control message", failure, INTERNAL_ERROR);
            return;
        }
        final ChannelFuture write;
        try {
            write = activeContext.writeAndFlush(frame);
        } catch (RuntimeException failure) {
            budget.release(wireBytes);
            failSession("WebSocket control write setup failed", failure, INTERNAL_ERROR);
            return;
        }
        write.addListener(future -> {
            budget.release(wireBytes);
            if (!future.isSuccess()) {
                activeContext.close();
                return;
            }
            if (closeAfterWrite) {
                activeContext.close();
            }
        });
    }

    private void beginClose(int code, String reason, boolean peerInitiated) {
        var activeContext = context;
        if (activeContext == null || !activeContext.channel().isActive()) {
            return;
        }
        if (closing) {
            if (peerInitiated) {
                cancelCloseDeadline();
                activeContext.close();
            }
            return;
        }
        closing = true;
        writeMandatoryControl(WebSocketMessage.close(code, reason), peerInitiated);
        scheduleCloseDeadline();
    }

    private void failSession(String reason, Throwable cause, int closeCode) {
        inbound.fail(reason, cause);
        sessionCancellation.cancel(reason);
        beginClose(closeCode, reasonForClose(reason), false);
    }

    private void abortAfterTerminalDeliveryRejection(Throwable cause) {
        sessionCancellation.cancel("WebSocket terminal callback executor rejected: " + cause.getClass().getSimpleName());
        closing = true;
        var activeContext = context;
        if (activeContext != null) {
            activeContext.close();
        }
    }

    private void scheduleCloseDeadline() {
        cancelCloseDeadline();
        var activeContext = context;
        if (activeContext == null || !activeContext.channel().isActive()) {
            return;
        }
        closeDeadline = activeContext.executor().schedule(
                (Runnable) activeContext::close,
                toNanosSaturated(limits.closeHandshakeTimeout()),
                TimeUnit.NANOSECONDS);
    }

    private void cancelCloseDeadline() {
        if (closeDeadline != null) {
            closeDeadline.cancel(false);
            closeDeadline = null;
        }
    }

    private void terminateTransport(ChannelHandlerContext context) {
        if (!transportTerminationStarted.compareAndSet(false, true)) {
            return;
        }
        closing = true;
        cancelCloseDeadline();
        sessionCancellation.cancel("WebSocket transport closed");
        completePendingWrites(new WebSocketClosedException("WebSocket connection closed"));
        outboundBudget.close();
        applicationControlBudget.close();
        mandatoryControlBudget.close();
        closeBudget.close();
        if (peerCloseReceived) {
            inbound.complete();
            closed.complete(null);
        } else {
            var failure = new WebSocketClosedException("WebSocket connection closed before peer close handshake");
            inbound.fail("WebSocket connection closed", failure);
            closed.completeExceptionally(failure);
        }
    }

    private void releasePendingWrite(CompletableFuture<Void> completion, Throwable failure) {
        final PendingWrite pending;
        synchronized (pendingWriteLock) {
            pending = pendingWrites.remove(completion);
        }
        if (pending == null) {
            return;
        }
        pending.budget().release(pending.wireBytes());
        if (failure == null) {
            completion.complete(null);
        } else {
            completion.completeExceptionally(failure);
        }
    }

    private void completePendingWrites(Throwable failure) {
        final java.util.List<Map.Entry<CompletableFuture<Void>, PendingWrite>> pending;
        synchronized (pendingWriteLock) {
            transportClosed = true;
            pending = new ArrayList<>(pendingWrites.entrySet());
            pendingWrites.clear();
        }
        for (var entry : pending) {
            entry.getValue().budget().release(entry.getValue().wireBytes());
            entry.getKey().completeExceptionally(failure);
        }
    }

    private boolean isTransportClosed() {
        synchronized (pendingWriteLock) {
            return transportClosed;
        }
    }

    private boolean executeOnEventLoop(Runnable action) {
        var activeContext = context;
        if (activeContext == null || !activeContext.channel().isActive()) {
            return false;
        }
        if (activeContext.executor().inEventLoop()) {
            action.run();
            return true;
        }
        try {
            activeContext.executor().execute(action);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void runOnEventLoop(Runnable action) {
        executeOnEventLoop(action);
    }

    private void validateOutbound(WebSocketMessage message) {
        if (message.size() > limits.maximumMessageBytes()) {
            throw new WebSocketLimitExceededException("WebSocket message exceeds maximumMessageBytes");
        }
        if (message.size() > limits.maximumFrameBytes()) {
            throw new WebSocketLimitExceededException("WebSocket message exceeds maximumFrameBytes");
        }
        if ((message.type() == WebSocketMessage.Type.PING || message.type() == WebSocketMessage.Type.PONG)
                && message.size() > 125) {
            throw new WebSocketLimitExceededException("WebSocket control message exceeds 125 bytes");
        }
    }

    private static WebSocketFrame toFrame(WebSocketMessage message) {
        return switch (message.type()) {
            case TEXT -> new TextWebSocketFrame(message.text().orElseThrow());
            case BINARY -> new BinaryWebSocketFrame(Unpooled.wrappedBuffer(message.bytes()));
            case PING -> new PingWebSocketFrame(Unpooled.wrappedBuffer(message.bytes()));
            case PONG -> new PongWebSocketFrame(Unpooled.wrappedBuffer(message.bytes()));
            case CLOSE -> new CloseWebSocketFrame(message.closeCode().orElseThrow(), message.closeReason().orElseThrow());
        };
    }

    private static byte[] copy(io.netty.buffer.ByteBuf buffer) {
        var copy = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), copy);
        return copy;
    }

    private static long estimatedWireBytes(WebSocketMessage message) {
        var payload = message.size() + (message.type() == WebSocketMessage.Type.CLOSE ? 2L : 0L);
        // Client data/control frames are masked. Reserve the largest legal frame header
        // (2+8+4 bytes) in addition to the detached application payload.
        return Math.addExact(payload, 14L);
    }

    private static String reasonForClose(String reason) {
        var bytes = reason.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 123) {
            return reason;
        }
        var bounded = new StringBuilder();
        var used = 0;
        for (var index = 0; index < reason.length();) {
            var codePoint = reason.codePointAt(index);
            var character = new String(Character.toChars(codePoint));
            var characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (used + characterBytes > 123) {
                break;
            }
            bounded.append(character);
            used += characterBytes;
            index += Character.charCount(codePoint);
        }
        return bounded.toString();
    }

    private static long toNanosSaturated(java.time.Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record PendingWrite(Budget budget, long wireBytes) {
    }

    /** Small internal accounting object; it owns no buffers and is safe across write callbacks. */
    private static final class Budget {
        private final long maximum;
        private long reserved;
        private boolean closed;

        private Budget(long maximum) {
            if (maximum <= 0) {
                throw new IllegalArgumentException("maximum must be positive");
            }
            this.maximum = maximum;
        }

        private synchronized boolean tryReserve(long bytes) {
            if (closed || bytes < 0 || bytes > maximum - reserved) {
                return false;
            }
            reserved += bytes;
            return true;
        }

        private synchronized void release(long bytes) {
            if (closed) {
                return;
            }
            if (bytes < 0 || bytes > reserved) {
                throw new IllegalStateException("invalid WebSocket outbound budget release");
            }
            reserved -= bytes;
        }

        private synchronized void close() {
            closed = true;
            reserved = 0;
        }
    }
}

