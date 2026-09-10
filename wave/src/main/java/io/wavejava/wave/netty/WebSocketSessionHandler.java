package io.wavejava.wave.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.RequestContext;
import io.wavejava.wave.api.websocket.WebSocket;
import io.wavejava.wave.api.websocket.WebSocketClosedException;
import io.wavejava.wave.api.websocket.WebSocketLimitExceededException;
import io.wavejava.wave.api.websocket.WebSocketLimits;
import io.wavejava.wave.api.websocket.WebSocketMessage;
import io.wavejava.wave.api.websocket.WebSocketSession;
import io.wavejava.wave.runtime.InvocationRuntime;
import io.wavejava.wave.runtime.OutboundByteBudget;
import io.wavejava.wave.runtime.RequestInvocation;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

/** Event-loop transport bridge for one already-handshaken WebSocket session. */
final class WebSocketSessionHandler extends SimpleChannelInboundHandler<WebSocketFrame> implements WebSocketSession {
    private static final int NORMAL_CLOSE = 1000;
    private static final int PROTOCOL_ERROR = 1002;
    private static final int MESSAGE_TOO_LARGE = 1009;
    private static final int INTERNAL_ERROR = 1011;

    // Transport-required control traffic must make protocol progress even when either application
    // data or application-generated Ping/Pong writes have used their complete budgets. A close has
    // its own one-frame reserve so queued pongs can never consume the capacity needed to
    // acknowledge a peer close.
    private static final long CONTROL_PLANE_MAXIMUM_BYTES = 512;
    private static final long CLOSE_PLANE_MAXIMUM_BYTES = 137; // 123-byte reason + maximum frame accounting.

    private final Request request;
    private final RequestContext sessionContext;
    private final WebSocketLimits limits;
    private final InvocationRuntime runtime;
    private final OutboundByteBudget outboundBudget;
    private final OutboundByteBudget applicationControlBudget = new OutboundByteBudget(CONTROL_PLANE_MAXIMUM_BYTES);
    private final OutboundByteBudget mandatoryControlBudget = new OutboundByteBudget(CONTROL_PLANE_MAXIMUM_BYTES);
    private final OutboundByteBudget closeBudget = new OutboundByteBudget(CLOSE_PLANE_MAXIMUM_BYTES);
    private final WebSocketInboundPublisher inbound;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Object pendingWriteLock = new Object();
    private final Map<CompletableFuture<Void>, PendingWrite> pendingWrites = new HashMap<>();
    private final AtomicReference<RequestInvocation<Void>> endpointInvocation = new AtomicReference<>();
    private final AtomicBoolean transportTerminationStarted = new AtomicBoolean();

    private volatile ChannelHandlerContext context;
    private volatile boolean closing;
    /** Reads are armed before the 101 is written so the first peer frame cannot be missed. */
    private volatile boolean handshakeReadsArmed;
    /** Becomes true only once the ordered 101 write has completed. */
    private volatile boolean transportActivated;
    private boolean transportClosed;
    private boolean peerCloseReceived;
    private boolean awaitingPong;
    private io.netty.util.concurrent.ScheduledFuture<?> closeDeadline;

    WebSocketSessionHandler(Request request, WebSocketLimits limits, InvocationRuntime runtime) {
        var initialRequest = Objects.requireNonNull(request, "request");
        sessionContext = sessionContextFor(initialRequest);
        this.request = initialRequest.withContext(sessionContext);
        this.limits = Objects.requireNonNull(limits, "limits");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        outboundBudget = new OutboundByteBudget(limits.maximumOutboundBytes());
        inbound = new WebSocketInboundPublisher(runtime::executeTransportCleanup, new WebSocketInboundPublisher.Listener() {
            @Override
            public void onReadCapacityChanged() {
                runOnEventLoop(WebSocketSessionHandler.this::updateReadState);
            }

            @Override
            public void onFailure(String reason, Throwable cause) {
                runOnEventLoop(() -> failSession(reason, cause, INTERNAL_ERROR));
            }

            @Override
            public void onTerminalDeliveryRejected(Throwable cause) {
                // The promised terminal callback cannot be silently dropped. The transport has no
                // safe application owner left, so cancel it and close rather than retaining the
                // session after its executor has stopped accepting cleanup work.
                runOnEventLoop(() -> abortAfterTerminalDeliveryRejection(cause));
            }
        });
    }

    /** Starts the endpoint callback after the successful ordered 101 write. */
    void startEndpoint(WebSocket endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (sessionContext.cancellationToken().isCancelled()) {
            return;
        }
        final RequestInvocation<Void> invocation;
        try {
            invocation = runtime.submit(request, sessionContext, ignored -> {
                endpoint.handle(this);
                return (Void) null;
            });
        } catch (RejectedExecutionException rejected) {
            runOnEventLoop(() -> failSession("WebSocket endpoint executor rejected callback", rejected, INTERNAL_ERROR));
            return;
        }

        // submit() starts a virtual thread before it returns. Publish immediately, then recheck
        // the shared cancellation token so channelInactive cannot miss the narrow submit/publish
        // race. If the channel won it, this also interrupts a just-started endpoint.
        var cancellationRegistration = sessionContext.cancellationToken().onCancellation(
                reason -> runtime.cancel(invocation, reason));
        if (!endpointInvocation.compareAndSet(null, invocation)) {
            cancellationRegistration.close();
            runtime.cancel(invocation, "WebSocket endpoint started more than once");
            return;
        }
        sessionContext.cancellationReason().ifPresent(reason -> runtime.cancel(invocation, reason));
        invocation.completion().whenComplete((ignored, failure) -> {
            cancellationRegistration.close();
            if (failure != null && !sessionContext.cancellationToken().isCancelled()) {
                runOnEventLoop(() -> failSession("WebSocket endpoint failed", failure, INTERNAL_ERROR));
            }
        });
    }

    @Override
    public Request request() {
        return request;
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
        } catch (RuntimeException validationFailure) {
            return CompletableFuture.failedFuture(validationFailure);
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
            cancelEndpoint(failure.getMessage());
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
        return transportActivated && activeContext != null && activeContext.channel().isActive()
                && !closing && !isTransportClosed();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        this.context = context;
        // A pipeline transition removes the ordinary HTTP dispatcher. Tie cancellation to the
        // channel's own close future as well as channelInactive so a disconnect cannot be missed
        // by inbound-event propagation order while the pipeline is being replaced.
        context.channel().closeFuture().addListener(ignored -> terminateTransport(context));
    }

    /**
     * Enables the pre-installed transport only after the ordered HTTP 101 is on the wire.
     *
     * <p>The session handler is deliberately placed in the pipeline before that write completes:
     * a peer is allowed to send its first WebSocket frame as soon as it observes the 101, which
     * otherwise leaves a narrow interval where the decoder has switched but no session handler
     * can own the frame.</p>
     */
    void armHandshakeReads() {
        handshakeReadsArmed = true;
        updateReadState();
    }

    void activateAfterHandshake() {
        transportActivated = true;
        updateReadState();
        // The 101 write listener can run while Netty is still unwinding the HTTP read/write
        // event. Queue one final read from the settled WebSocket pipeline: this is not a timer
        // and it closes the read-interest window for a peer frame already queued in the socket.
        var activeContext = context;
        if (activeContext != null) {
            try {
                activeContext.executor().execute(this::updateReadState);
            } catch (RejectedExecutionException ignored) {
                // channelInactive owns terminal cleanup once the EventLoop has stopped.
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
        if (!transportActivated) {
            // A peer that sends frames before the successful ordered handshake cannot safely be
            // associated with an application session. Do not reinterpret or retain that input.
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
            // Pings are protocol control, not application-data Flow work. Reply immediately even
            // when one data item is retained for a subscriber with zero demand.
            writeMandatoryControl(WebSocketMessage.pong(copy(ping.content())), false);
            return;
        }
        if (frame instanceof PongWebSocketFrame) {
            // Heartbeat progress is transport-owned and must never consume application demand.
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
            // Retain only this terminal notification. The wire close reply starts now; delivery
            // to application code waits for Flow demand and cannot delay protocol progress.
            inbound.completeAfterPeerClose(peerClose);
            if (closing) {
                cancelEndpoint("WebSocket peer close acknowledged");
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
            // ALLOW_HALF_CLOSURE is disabled on the listener, but retain this defensive path for
            // transports/configurations that still surface an input-side EOF event.
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
        failSession("WebSocket transport failure", cause, INTERNAL_ERROR);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        terminateTransport(context);
        context.fireChannelInactive();
    }

    private void terminateTransport(ChannelHandlerContext context) {
        if (!transportTerminationStarted.compareAndSet(false, true)) {
            return;
        }
        closing = true;
        cancelCloseDeadline();
        cancelEndpoint("WebSocket transport closed");
        completePendingWrites(new WebSocketClosedException("WebSocket connection closed"));
        outboundBudget.close();
        applicationControlBudget.close();
        mandatoryControlBudget.close();
        closeBudget.close();
        if (peerCloseReceived) {
            // A peer close may still be waiting for subscriber demand; complete() is intentionally
            // a no-op in that case so the ordered CLOSE notification is not erased.
            inbound.complete();
            closed.complete(null);
        } else {
            var closedFailure = new WebSocketClosedException("WebSocket connection closed before peer close handshake");
            inbound.fail("WebSocket connection closed", closedFailure);
            closed.completeExceptionally(closedFailure);
        }
    }

    private void offerInbound(WebSocketMessage message) {
        if (!inbound.offer(message)) {
            beginClose(MESSAGE_TOO_LARGE, "inbound consumer is too slow", false);
        }
        updateReadState();
    }

    private CompletionStage<Void> reserveAndDispatch(
            WebSocketMessage message,
            OutboundByteBudget budget,
            String exhaustedMessage,
            boolean closeOnBudgetExhaustion) {
        var completion = new CompletableFuture<Void>();
        var pending = new PendingWrite(budget, estimatedWireBytes(message));
        Throwable failure = null;
        var exhausted = false;

        // Registration and event-loop admission share one lock with channelInactive. Therefore a
        // just-closed transport either owns and fails the future here, or owns it in its teardown
        // sweep; no successful reservation can be left without a completion path.
        synchronized (pendingWriteLock) {
            if (transportClosed || !isOpen()) {
                failure = new WebSocketClosedException("WebSocket session is closed");
            } else if (!budget.tryReserve(pending.wireBytes()).accepted()) {
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

    /** Writes transport-required Ping/Pong/Close frames from the connection event loop. */
    private void writeMandatoryControl(WebSocketMessage message, boolean closeAfterWrite) {
        var activeContext = context;
        if (activeContext == null || !activeContext.channel().isActive()) {
            return;
        }
        var budget = message.type() == WebSocketMessage.Type.CLOSE ? closeBudget : mandatoryControlBudget;
        var wireBytes = estimatedWireBytes(message);
        if (!budget.tryReserve(wireBytes).accepted()) {
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
                cancelEndpoint("WebSocket peer initiated close");
                cancelCloseDeadline();
                activeContext.close();
            }
            return;
        }
        closing = true;
        if (peerInitiated) {
            cancelEndpoint("WebSocket peer initiated close");
        }
        updateReadState();
        writeMandatoryControl(WebSocketMessage.close(code, reason), peerInitiated);
        // Bound both a locally waiting close handshake and a peer-initiated reply whose socket
        // write is stuck behind a slow peer.
        scheduleCloseDeadline();
    }

    private void failSession(String reason, Throwable cause, int closeCode) {
        inbound.fail(reason, cause);
        cancelEndpoint(reason);
        beginClose(closeCode, reasonForClose(reason), false);
    }

    private void abortAfterTerminalDeliveryRejection(Throwable cause) {
        cancelEndpoint("WebSocket terminal callback executor rejected: " + cause.getClass().getSimpleName());
        closing = true;
        var activeContext = context;
        if (activeContext != null) {
            activeContext.close();
        }
    }

    private void cancelEndpoint(String reason) {
        sessionContext.cancellationToken().cancel(reason);
        var invocation = endpointInvocation.get();
        if (invocation != null) {
            runtime.cancel(invocation, reason);
        }
    }

    private void scheduleCloseDeadline() {
        cancelCloseDeadline();
        var activeContext = context;
        if (activeContext == null || !activeContext.channel().isActive()) {
            return;
        }
        final long delayNanos;
        try {
            delayNanos = limits.closeHandshakeTimeout().toNanos();
        } catch (ArithmeticException ignored) {
            // A practically infinite user duration is still represented by Netty's maximum delay.
            closeDeadline = activeContext.executor().schedule(
                    (Runnable) activeContext::close, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            return;
        }
        closeDeadline = activeContext.executor().schedule(
                (Runnable) activeContext::close, Math.max(0, delayNanos), TimeUnit.NANOSECONDS);
    }

    private void cancelCloseDeadline() {
        if (closeDeadline != null) {
            closeDeadline.cancel(false);
            closeDeadline = null;
        }
    }

    private void updateReadState() {
        var activeContext = context;
        if (!handshakeReadsArmed || activeContext == null || !activeContext.channel().isActive()) {
            return;
        }
        // Do not pause the socket behind a slow application subscriber: doing so would also hide
        // control Ping/Pong/Close frames and break heartbeat or close-handshake progress. The
        // inbound bridge retains one data message only; another application data message is
        // rejected with a bounded close, while control frames receive protocol-priority work.
        if (!activeContext.channel().config().isAutoRead()) {
            activeContext.channel().config().setAutoRead(true);
        }
        activeContext.channel().read();
    }

    private void runOnEventLoop(Runnable action) {
        executeOnEventLoop(action);
    }

    /**
     * Attempts to admit work to the connection event loop.
     *
     * <p>Callers that own a budget reservation must use the boolean result while holding their
     * registration lock so rejected dispatch atomically rescinds the reservation.</p>
     */
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
            // channelInactive owns terminal cleanup once the EventLoop has stopped.
            return false;
        }
    }

    private void validateOutbound(WebSocketMessage message) {
        if (message.size() > limits.maximumMessageBytes()) {
            throw new WebSocketLimitExceededException("WebSocket message exceeds maximumMessageBytes");
        }
        // 0.5 emits one complete application message per data frame. Reject rather than silently
        // fragmenting so the configured frame budget remains a real per-write memory boundary.
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
        // Server frames are unmasked. Reserve the largest legal header (10 bytes) plus a small
        // accounting margin so frame metadata cannot escape a session's finite budget.
        return Math.addExact(message.size(), 14L);
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

    private static String reasonForClose(String reason) {
        // RFC 6455 limits a close reason to 123 UTF-8 bytes. Framework-internal reasons are
        // intentionally short and this final truncation keeps an unexpected diagnostic safe.
        var bytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 123) {
            return reason;
        }
        var bounded = new StringBuilder();
        var used = 0;
        for (var index = 0; index < reason.length();) {
            var codePoint = reason.codePointAt(index);
            var character = new String(Character.toChars(codePoint));
            var characterBytes = character.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (used + characterBytes > 123) {
                break;
            }
            bounded.append(character);
            used += characterBytes;
            index += Character.charCount(codePoint);
        }
        return bounded.toString();
    }

    /** Creates a session context without inheriting the ordinary finite HTTP request deadline. */
    private static RequestContext sessionContextFor(Request request) {
        var initial = request.context().orElse(null);
        var builder = RequestContext.builder(initial == null ? UUID.randomUUID().toString() : initial.requestId());
        if (initial != null) {
            builder.data(initial.data());
        }
        return builder.build();
    }

    private record PendingWrite(OutboundByteBudget budget, long wireBytes) {
    }
}
