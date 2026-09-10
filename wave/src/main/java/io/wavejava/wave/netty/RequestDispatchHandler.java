package io.wavejava.wave.netty;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;
import io.netty.util.ReferenceCountUtil;
import io.wavejava.wave.api.http.Body;
import io.wavejava.wave.api.http.Deadline;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.RequestContext;
import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.api.observability.AccessLogEvent;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.api.server.RequestBodyMode;
import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import io.wavejava.wave.api.websocket.WebSocketLimits;
import io.wavejava.wave.runtime.ConnectionSequencer;
import io.wavejava.wave.runtime.RequestDispatcher;
import io.wavejava.wave.runtime.InboundFlowBridge;
import io.wavejava.wave.runtime.InvocationRuntime;
import io.wavejava.wave.runtime.ObservabilityDispatcher;
import io.wavejava.wave.runtime.RequestInvocation;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Converts bounded Netty requests into framework values and dispatches application work away from
 * the event loop.
 *
 * <p>One handler instance is installed per connection. It assigns ingress sequence numbers on the
 * connection event loop, lets virtual threads run application code and response encoding, then
 * returns only prepared response bytes to the event loop. {@link ConnectionSequencer} prevents a
 * faster later request from overtaking an earlier HTTP/1.1 response and bounds both admitted
 * requests and bytes retained behind that response.</p>
 */
final class RequestDispatchHandler extends ChannelInboundHandlerAdapter {
    private static final String DEADLINE_EXCEEDED = "deadline exceeded";
    private static final String SERVER_SHUTDOWN = "server shutdown";

    private final RequestDispatcher application;
    private final InvocationRuntime runtime;
    private final ServerLimits limits;
    private final ServerTimeouts timeouts;
    private final Semaphore inFlightRequests;
    private final RequestBodyMode requestBodyMode;
    private final ObservabilityDispatcher observability;
    private final ForwardedHeaderPolicy forwardedHeaders;
    private final ConnectionSequencer<PreparedResponse> sequencer;
    private final Map<ConnectionSequencer.Ticket, RequestInvocation<PreparedResponse>> activeInvocations = new HashMap<>();
    private final Map<ConnectionSequencer.Ticket, PreparedResponse> pendingResponses = new HashMap<>();
    private final Map<ConnectionSequencer.Ticket, RequestContext> streamContexts = new HashMap<>();
    private final Map<ConnectionSequencer.Ticket, io.netty.util.concurrent.ScheduledFuture<?>> streamDeadlines = new HashMap<>();
    private final Map<ConnectionSequencer.Ticket, InboundFlowBridge> inboundStreams = new HashMap<>();
    /** Terminal request publishers remain tracked only until their handler or callback finishes. */
    private final Map<ConnectionSequencer.Ticket, InboundFlowBridge> terminalInboundStreams = new HashMap<>();
    private final Map<ConnectionSequencer.Ticket, RequestContext> inboundContexts = new HashMap<>();
    /** EventLoop-owned records that bridge application completion to final transport completion. */
    private final Map<ConnectionSequencer.Ticket, RequestObservation> observations = new HashMap<>();
    /** At most one active HTTP body may have a response committed ahead of its last byte. */
    private final Set<ConnectionSequencer.Ticket> inboundResponseCommitted = new HashSet<>();
    private final Deque<ConnectionSequencer.Ready<PreparedResponse>> writeQueue = new ArrayDeque<>();

    private boolean readsPaused;
    private boolean terminalInputPaused;
    private boolean closing;
    private boolean responseWriteInProgress;
    /** Set as soon as a validly isolated WebSocket response is selected, before its ordered 101 write. */
    private boolean webSocketUpgradePending;
    private boolean inboundReadPending;
    private int streamingInboundObjectCount;
    private FlowBridge activeStream;
    private StreamingInbound activeInbound;
    private String closingReason = "transport failure";

    RequestDispatchHandler(
            RequestDispatcher application,
            InvocationRuntime runtime,
            ServerLimits limits,
            ServerTimeouts timeouts,
            Semaphore inFlightRequests,
            RequestBodyMode requestBodyMode,
            ObservabilityDispatcher observability,
            ForwardedHeaderPolicy forwardedHeaders) {
        this.application = Objects.requireNonNull(application, "application");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.inFlightRequests = Objects.requireNonNull(inFlightRequests, "inFlightRequests");
        this.requestBodyMode = Objects.requireNonNull(requestBodyMode, "requestBodyMode");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.forwardedHeaders = Objects.requireNonNull(forwardedHeaders, "forwardedHeaders");
        sequencer = new ConnectionSequencer<>(
                limits.maximumPendingRequestsPerConnection(),
                limits.maximumPendingResponseBytesPerConnection());
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        try {
            if (requestBodyMode == RequestBodyMode.AGGREGATED) {
                if (message instanceof FullHttpRequest inbound) {
                    handleAggregatedRequest(context, inbound);
                } else {
                    abortConnection(context, "aggregate HTTP/1.1 decoder emitted an incomplete request");
                }
            } else {
                streamingInboundObjectCount++;
                handleStreamingMessage(context, message);
            }
        } finally {
            ReferenceCountUtil.release(message);
        }
    }

    private void handleAggregatedRequest(ChannelHandlerContext context, FullHttpRequest inbound) {
        if (closing) {
            return;
        }
        if (webSocketUpgradePending) {
            // The upgrade has stopped HTTP intake. A subsequently decoded request would otherwise
            // be ambiguous when this pipeline becomes a WebSocket frame decoder.
            abortConnection(context, "HTTP/1.1 input arrived after a pending WebSocket upgrade");
            return;
        }

        var admission = sequencer.tryAdmit();
        if (!admission.accepted()) {
            // Auto-read is disabled as soon as the last permitted request was admitted. Reaching
            // this branch means Netty had already decoded one extra request, which cannot be
            // safely retained or silently discarded without violating HTTP/1.1 ordering.
            abortConnection(context, "per-connection pending-response budget exhausted");
            return;
        }
        applyReadBackpressure(context, admission.snapshot());

        var ticket = admission.ticket();
        var version = inbound.protocolVersion();
        var requestMethod = inbound.method().name();
        var keepAlive = HttpUtil.isKeepAlive(inbound);
        if (!Http1RequestFraming.isSafe(inbound)) {
            completePrepared(context, ticket, PreparedResponse.error(400, "Bad Request", version, requestMethod, false));
            return;
        }
        if (inbound.decoderResult().isFailure()) {
            completePrepared(
                    context,
                    ticket,
                    PreparedResponse.error(400, "Bad Request", version, requestMethod, keepAlive));
            return;
        }

        final Request request;
        try {
            request = toRequest(context, inbound);
        } catch (IllegalArgumentException failure) {
            completePrepared(
                    context,
                    ticket,
                    PreparedResponse.error(400, "Bad Request", version, requestMethod, keepAlive));
            return;
        }

        dispatchRequest(context, ticket, request, version, requestMethod, keepAlive);
    }

    /** Decodes a non-aggregated HTTP/1.1 request in explicitly selected streaming mode. */
    private void handleStreamingMessage(ChannelHandlerContext context, Object message) {
        if (closing) {
            return;
        }
        if (webSocketUpgradePending) {
            abortConnection(context, "HTTP/1.1 input arrived after a pending WebSocket upgrade");
            return;
        }
        if (message instanceof FullHttpRequest inbound) {
            handleStreamingRequest(context, inbound);
            if (!closing) {
                handleStreamingContent(context, inbound, true);
            }
            return;
        }
        if (message instanceof HttpRequest inbound) {
            handleStreamingRequest(context, inbound);
            return;
        }
        if (message instanceof HttpContent inbound) {
            handleStreamingContent(context, inbound, inbound instanceof LastHttpContent);
            return;
        }
        abortConnection(context, "HTTP/1.1 decoder emitted an unsupported request object");
    }

    private void handleStreamingRequest(ChannelHandlerContext context, HttpRequest inbound) {
        if (activeInbound != null) {
            abortConnection(context, "received a new HTTP request before the streaming body ended");
            return;
        }

        var admission = sequencer.tryAdmit();
        if (!admission.accepted()) {
            abortConnection(context, "per-connection pending-response budget exhausted");
            return;
        }
        applyReadBackpressure(context, admission.snapshot());

        var ticket = admission.ticket();
        var version = inbound.protocolVersion();
        var requestMethod = inbound.method().name();
        var keepAlive = HttpUtil.isKeepAlive(inbound);
        if (!Http1RequestFraming.isSafe(inbound)) {
            pauseInputForTerminalError(context);
            completePrepared(context, ticket, PreparedResponse.error(400, "Bad Request", version, requestMethod, false));
            return;
        }
        if (inbound.decoderResult().isFailure()) {
            pauseInputForTerminalError(context);
            completePrepared(
                    context,
                    ticket,
                    PreparedResponse.error(400, "Bad Request", version, requestMethod, false));
            return;
        }
        if (declaredContentLengthExceedsLimit(inbound)) {
            // Fail before dispatching application code or reading a single body byte. The ticket
            // keeps this final 413 ordered behind an earlier pipelined response if one exists.
            pauseInputForTerminalError(context);
            completePrepared(
                    context,
                    ticket,
                    PreparedResponse.error(413, "Payload Too Large", version, requestMethod, false));
            return;
        }

        var bridgeReference = new AtomicReference<InboundFlowBridge>();
        var bridge = new InboundFlowBridge(
                runtime::executeTransportCleanup,
                limits.maximumRequestBodyBytes(),
                new InboundFlowBridge.Listener() {
                    @Override
                    public void onDemandAvailable() {
                        runOnEventLoop(context, () -> requestInboundRead(context, ticket));
                    }

                    @Override
                    public void onComplete() {
                        runOnEventLoop(context, () -> completeInboundPublisher(ticket, bridgeReference.get()));
                    }

                    @Override
                    public void onFailure(String reason, Throwable cause) {
                        runOnEventLoop(context, () -> failInboundPublisher(
                                context, ticket, bridgeReference.get(), reason));
                    }

                    @Override
                    public void onCancelled() {
                        runOnEventLoop(context, () -> cancelInboundPublisher(context, ticket, bridgeReference.get()));
                    }
                });
        bridgeReference.set(bridge);
        final Request request;
        try {
            request = toStreamingRequest(context, inbound, bridge);
        } catch (IllegalArgumentException failure) {
            pauseInputForTerminalError(context);
            completePrepared(
                    context,
                    ticket,
                    PreparedResponse.error(400, "Bad Request", version, requestMethod, false));
            return;
        }

        activeInbound = new StreamingInbound(ticket, bridge);
        inboundStreams.put(ticket, bridge);
        updateReadState(context);
        dispatchRequest(context, ticket, request, version, requestMethod, keepAlive);
        if (!closing && hasKnownEmptyBody(inbound)) {
            // A zero-length request has no item that needs Flow demand. Pull its terminal marker
            // eagerly so a handler that never subscribes can still safely keep this connection.
            requestInboundRead(context, ticket);
        }
    }

    private void handleStreamingContent(
            ChannelHandlerContext context, HttpContent inbound, boolean terminalContent) {
        var active = activeInbound;
        if (active == null) {
            abortConnection(context, "received HTTP request content without a streaming request head");
            return;
        }

        var content = new byte[inbound.content().readableBytes()];
        inbound.content().getBytes(inbound.content().readerIndex(), content);
        if (!active.bridge().offer(content, terminalContent)) {
            return;
        }
        if (terminalContent) {
            activeInbound = null;
            inboundReadPending = false;
            // No more socket input is needed for this publisher. Keep it only while its handler
            // or final callback still needs a bounded lifecycle decision; a late subscriber owns
            // the publisher itself, not an unbounded connection-side map entry.
            inboundStreams.remove(active.ticket(), active.bridge());
            terminalInboundStreams.put(active.ticket(), active.bridge());
            updateReadState(context);
            pumpBufferedStreamingObject(context);
        }
    }

    private void dispatchRequest(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            Request request,
            HttpVersion version,
            String requestMethod,
            boolean keepAlive) {
        if (!inFlightRequests.tryAcquire()) {
            completePrepared(
                    context,
                    ticket,
                    PreparedResponse.error(503, "Service Unavailable", version, requestMethod,
                            request.hasStreamingBody() ? false : keepAlive));
            return;
        }

        var requestContext = RequestContext.builder(UUID.randomUUID().toString())
                .deadline(Deadline.after(timeouts.requestTimeout()))
                .build();
        var observation = new RequestObservation(requestContext, request.method().name());
        observations.put(ticket, observation);
        try {
            var invocation = runtime.submit(
                    request,
                    requestContext,
                    invocationRequest -> {
                        var dispatch = application.dispatch(invocationRequest, observation::routeObserved);
                        return PreparedResponse.render(
                                dispatch.response(),
                                version,
                                requestMethod,
                                keepAlive,
                                invocationRequest,
                                dispatch.outcome(),
                                dispatch.routePattern());
                    });
            activeInvocations.put(ticket, invocation);
            if (request.hasStreamingBody()) {
                inboundContexts.put(ticket, requestContext);
            }
            invocation.completion().whenComplete((prepared, failure) -> {
                inFlightRequests.release();
                scheduleCompletion(context, ticket, invocation, prepared, failure, version, requestMethod, keepAlive);
            });
        } catch (RejectedExecutionException rejected) {
            inFlightRequests.release();
            observations.remove(ticket);
            completePrepared(
                    context,
                    ticket,
                    PreparedResponse.error(503, "Service Unavailable", version, requestMethod,
                            request.hasStreamingBody() ? false : keepAlive));
        }
    }

    private Request toStreamingRequest(
            ChannelHandlerContext context, HttpRequest inbound, InboundFlowBridge bridge) {
        var headers = Headers.builder();
        for (Map.Entry<String, String> entry : inbound.headers()) {
            headers.add(entry.getKey(), entry.getValue());
        }
        var target = inbound.uri();
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("only origin-form request targets are supported");
        }
        SocketAddress remoteAddress = context.channel().remoteAddress();
        var request = Request.builder()
                .method(HttpMethod.of(inbound.method().name()))
                .version(toWaveVersion(inbound.protocolVersion()))
                .scheme(context.pipeline().get(SslHandler.class) == null ? "http" : "https")
                .target(target)
                .headers(headers.build())
                .bodyPublisher(bridge)
                .remoteAddress(remoteAddress)
                .build();
        return forwardedHeaders.apply(request);
    }

    private void requestInboundRead(ChannelHandlerContext context, ConnectionSequencer.Ticket ticket) {
        if (closing || activeInbound == null || !activeInbound.ticket().equals(ticket)) {
            return;
        }
        inboundReadPending = true;
        issuePendingInboundRead(context);
    }

    private void issuePendingInboundRead(ChannelHandlerContext context) {
        if (closing || readsPaused || activeInbound == null || !inboundReadPending || !context.channel().isActive()) {
            return;
        }
        inboundReadPending = false;
        if (pumpBufferedStreamingObject(context)) {
            return;
        }
        context.read();
    }

    /**
     * Consumes at most one decoder-cumulated object before asking the kernel for more bytes.
     * This is what makes two HTTP chunks coalesced in one socket write follow Flow demand instead
     * of becoming an unbounded adapter queue.
     */
    private boolean pumpBufferedStreamingObject(ChannelHandlerContext context) {
        if (requestBodyMode != RequestBodyMode.STREAMING || closing || !context.channel().isActive()) {
            return false;
        }
        var decoder = context.pipeline().get(DemandDrivenHttp1Codec.DemandDrivenHttpRequestDecoder.class);
        var decoderContext = context.pipeline().context(DemandDrivenHttp1Codec.DemandDrivenHttpRequestDecoder.class);
        if (decoder == null || decoderContext == null) {
            return false;
        }
        var before = streamingInboundObjectCount;
        decoder.decodePending(decoderContext);
        return streamingInboundObjectCount != before;
    }

    private void completeInboundPublisher(ConnectionSequencer.Ticket ticket, InboundFlowBridge bridge) {
        if (bridge != null && removeInboundTracking(ticket, bridge)) {
            inboundContexts.remove(ticket);
        }
        inboundResponseCommitted.remove(ticket);
    }

    private void failInboundPublisher(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            InboundFlowBridge bridge,
            String reason) {
        if (bridge == null || !removeInboundTracking(ticket, bridge)) {
            return;
        }
        var requestContext = inboundContexts.remove(ticket);
        if (requestContext != null) {
            requestContext.cancellationToken().cancel(reason);
        }
        if ("streaming request body exceeded its byte budget".equals(reason)) {
            if (inboundResponseCommitted.remove(ticket) || hasEarlierOutstandingResponse(ticket)) {
                abortConnection(context, "streaming request body exceeded its byte budget after response commit");
            } else {
                writeTerminalError(context, 413, "Payload Too Large");
            }
            return;
        }
        abortConnection(context, reason);
    }

    private void cancelInboundPublisher(
            ChannelHandlerContext context, ConnectionSequencer.Ticket ticket, InboundFlowBridge bridge) {
        if (bridge == null || !removeInboundTracking(ticket, bridge)) {
            return;
        }
        var requestContext = inboundContexts.remove(ticket);
        inboundResponseCommitted.remove(ticket);
        if (requestContext != null) {
            requestContext.cancellationToken().cancel("request body subscription cancelled");
        }
        if (!closing) {
            abortConnection(context, "request body subscription cancelled");
        }
    }

    private boolean removeInboundTracking(ConnectionSequencer.Ticket ticket, InboundFlowBridge bridge) {
        return inboundStreams.remove(ticket, bridge) || terminalInboundStreams.remove(ticket, bridge);
    }

    /** A raw terminal error may not overtake any earlier admitted HTTP/1.1 request. */
    private boolean hasEarlierOutstandingResponse(ConnectionSequencer.Ticket ticket) {
        return activeInvocations.keySet().stream().anyMatch(candidate -> candidate.sequence() < ticket.sequence())
                || pendingResponses.keySet().stream().anyMatch(candidate -> candidate.sequence() < ticket.sequence())
                || writeQueue.stream().anyMatch(ready -> ready.ticket().sequence() < ticket.sequence());
    }

    private boolean declaredContentLengthExceedsLimit(HttpRequest inbound) {
        try {
            return HttpUtil.getContentLength(inbound, -1) > limits.maximumRequestBodyBytes();
        } catch (RuntimeException ignored) {
            // Netty's decoder result owns malformed header handling.
            return false;
        }
    }

    private static boolean hasKnownEmptyBody(HttpRequest inbound) {
        try {
            return !HttpUtil.isTransferEncodingChunked(inbound) && HttpUtil.getContentLength(inbound, 0) == 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void pauseInputForTerminalError(ChannelHandlerContext context) {
        terminalInputPaused = true;
        context.channel().config().setAutoRead(false);
    }

    private void runOnEventLoop(ChannelHandlerContext context, Runnable action) {
        if (context.executor().inEventLoop()) {
            action.run();
            return;
        }
        try {
            context.executor().execute(action);
        } catch (RejectedExecutionException ignored) {
            // The connection close path owns cleanup once its event loop stops.
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        closeOutstanding(context, "client disconnected");
        context.fireChannelInactive();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext context) {
        if (activeStream != null) {
            activeStream.onChannelWritabilityChanged();
        }
        context.fireChannelWritabilityChanged();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent) {
            abortConnection(context, "connection idle timeout");
            return;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        if (cause instanceof TooLongFrameException && sequencer.snapshot().budget().pendingResponses() == 0) {
            writeTerminalError(context, 413, "Payload Too Large");
            return;
        }
        if (cause instanceof ReadTimeoutException || cause instanceof WriteTimeoutException) {
            abortConnection(context, "connection I/O timeout");
            return;
        }
        abortConnection(context, "transport failure");
    }

    private Request toRequest(ChannelHandlerContext context, FullHttpRequest inbound) {
        var content = new byte[inbound.content().readableBytes()];
        inbound.content().getBytes(inbound.content().readerIndex(), content);
        var headers = Headers.builder();
        for (Map.Entry<String, String> entry : inbound.headers()) {
            headers.add(entry.getKey(), entry.getValue());
        }
        var target = inbound.uri();
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("only origin-form request targets are supported");
        }
        SocketAddress remoteAddress = context.channel().remoteAddress();
        var request = Request.builder()
                .method(HttpMethod.of(inbound.method().name()))
                .version(toWaveVersion(inbound.protocolVersion()))
                .scheme(context.pipeline().get(SslHandler.class) == null ? "http" : "https")
                .target(target)
                .headers(headers.build())
                .body(Body.of(content, limits.maximumRequestBodyBytes()))
                .remoteAddress(remoteAddress)
                .build();
        return forwardedHeaders.apply(request);
    }

    private void scheduleCompletion(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            RequestInvocation<PreparedResponse> invocation,
            PreparedResponse prepared,
            Throwable failure,
            HttpVersion version,
            String requestMethod,
            boolean keepAlive) {
        try {
            context.executor().execute(() -> completeInvocation(
                    context, ticket, invocation, prepared, failure, version, requestMethod, keepAlive));
        } catch (RejectedExecutionException rejected) {
            if (prepared != null) {
                prepared.abort();
            }
        }
    }

    private void completeInvocation(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            RequestInvocation<PreparedResponse> invocation,
            PreparedResponse prepared,
            Throwable failure,
            HttpVersion version,
            String requestMethod,
            boolean keepAlive) {
        activeInvocations.remove(ticket);
        var observation = observations.get(ticket);
        var completed = failure == null
                ? prepared
                : responseForFailure(
                        invocation,
                        failure,
                        version,
                        requestMethod,
                        keepAlive,
                        observation == null ? "<pending>" : observation.route());
        if (completed == null) {
            abortConnection(context, "application completion did not produce a response");
            return;
        }
        if (observation != null) {
            observation.applicationCompleted(completed);
        }
        if (closing || !context.channel().isActive()) {
            completed.abort();
            return;
        }
        var inbound = inboundStreams.get(ticket);
        if (inbound != null && !inbound.isSourceCompleted()) {
            inboundResponseCommitted.add(ticket);
            completed = completed.closeAfterWrite();
        } else {
            inbound = terminalInboundStreams.remove(ticket);
            if (inbound != null) {
                inboundContexts.remove(ticket);
                inboundResponseCommitted.remove(ticket);
                if (!inbound.isTransportReleasable()) {
                    // A terminal chunk can still have a pending or executing application callback.
                    // Do not reuse this HTTP/1.1 connection after the handler exits; explicitly
                    // cancel the publisher so that callback receives a terminal signal off-loop.
                    invocation.context().cancellationToken().cancel("handler completed before request body delivery");
                    inbound.cancel("handler completed before request body delivery");
                    completed = completed.closeAfterWrite();
                }
            }
        }
        if (completed.isStreaming()) {
            streamContexts.put(ticket, invocation.context());
            scheduleStreamDeadline(context, ticket, invocation.context());
        }
        if (completed.isWebSocket() && !claimWebSocketUpgrade(context, ticket, completed)) {
            completed.abort();
            completed = PreparedResponse.error(
                    400,
                    "Bad Request",
                    version,
                    requestMethod,
                    false,
                    completed.applicationOutcome(),
                    completed.routePattern());
        }
        completePrepared(context, ticket, completed);
    }

    /**
     * Stops HTTP intake once a WebSocket response is selected, but only when no later HTTP
     * request has already been admitted. This prevents raw pipelined HTTP bytes from becoming
     * WebSocket frames after the codec transition while still allowing the 101 to wait behind an
     * earlier ordered response.
     */
    private boolean claimWebSocketUpgrade(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared) {
        var request = prepared.webSocketRequest();
        if (request.hasStreamingBody()) {
            return false;
        }
        var snapshot = sequencer.snapshot();
        if (snapshot.nextIngressSequence() != ticket.sequence() + 1) {
            return false;
        }
        webSocketUpgradePending = true;
        terminalInputPaused = true;
        readsPaused = true;
        context.channel().config().setAutoRead(false);
        return true;
    }

    private static PreparedResponse responseForFailure(
            RequestInvocation<PreparedResponse> invocation,
            Throwable failure,
            HttpVersion version,
            String requestMethod,
            boolean keepAlive,
            String routePattern) {
        var reason = invocation.context().cancellationReason().orElse("");
        if (DEADLINE_EXCEEDED.equals(reason)) {
            return PreparedResponse.error(
                    504,
                    "Gateway Timeout",
                    version,
                    requestMethod,
                    keepAlive,
                    Outcome.failure(Outcome.Kind.DEADLINE_EXCEEDED, failure),
                    routePattern);
        }
        if (SERVER_SHUTDOWN.equals(reason)) {
            return PreparedResponse.error(
                    503,
                    "Service Unavailable",
                    version,
                    requestMethod,
                    keepAlive,
                    Outcome.failure(Outcome.Kind.CLIENT_CANCELLATION, failure),
                    routePattern);
        }
        if (!reason.isBlank()) {
            return PreparedResponse.error(
                    500,
                    "Internal Server Error",
                    version,
                    requestMethod,
                    keepAlive,
                    Outcome.failure(Outcome.Kind.CLIENT_CANCELLATION, failure),
                    routePattern);
        }
        return PreparedResponse.error(
                500,
                "Internal Server Error",
                version,
                requestMethod,
                keepAlive,
                Outcome.failure(Outcome.Kind.APPLICATION_FAILURE, failure),
                routePattern);
    }

    private void completePrepared(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared) {
        if (closing || runtime.isShutdown() || !context.channel().isActive()) {
            recordTransportResult(
                    ticket,
                    prepared,
                    runtime.isShutdown() || closing
                            ? AccessLogEvent.TransportOutcome.CANCELLED
                            : transportOutcomeForClose(closingReason));
            prepared.abort();
            return;
        }

        pendingResponses.put(ticket, prepared);
        final ConnectionSequencer.Completion<PreparedResponse> completion;
        try {
            completion = sequencer.complete(ticket, prepared, prepared.retainedBytes());
        } catch (RuntimeException sequencingFailure) {
            pendingResponses.remove(ticket);
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            abortConnection(context, "response sequencing failure");
            return;
        }
        if (!completion.accepted()) {
            pendingResponses.remove(ticket);
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            abortConnection(context, "per-connection response-byte budget exhausted");
            return;
        }

        enqueueReadyResponses(context, completion.ready());
        applyReadBackpressure(context, completion.snapshot());
    }

    private void enqueueReadyResponses(
            ChannelHandlerContext context,
            java.util.List<ConnectionSequencer.Ready<PreparedResponse>> readyResponses) {
        writeQueue.addAll(readyResponses);
        writeNextResponse(context);
    }

    /**
     * Writes one sequenced response at a time. A stream keeps this slot until its final chunk is
     * acknowledged, so a pipelined successor cannot appear between stream chunks.
     */
    private void writeNextResponse(ChannelHandlerContext context) {
        if (responseWriteInProgress || closing || !context.channel().isActive()) {
            return;
        }
        var ready = writeQueue.pollFirst();
        if (ready == null) {
            return;
        }

        responseWriteInProgress = true;
        var prepared = ready.payload();
        if (prepared.isWebSocket()) {
            writeWebSocketUpgrade(context, ready.ticket(), prepared);
            return;
        }
        if (prepared.isStreaming()) {
            writeStreamingResponse(context, ready.ticket(), prepared);
            return;
        }
        final ChannelFuture write;
        try {
            write = ResponseWriteHandler.write(context, prepared);
        } catch (RuntimeException writeFailure) {
            recordTransportFailure(ready.ticket(), prepared);
            prepared.abort();
            abortConnection(context, "response write setup failed");
            return;
        }
        write.addListener(future -> onAggregateWriteCompletion(context, ready.ticket(), prepared, future));
    }

    private void writeStreamingResponse(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared) {
        final ChannelFuture headers;
        try {
            headers = ResponseWriteHandler.writeStreamHeaders(context, prepared);
        } catch (RuntimeException writeFailure) {
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            abortConnection(context, "response stream header write setup failed");
            return;
        }
        headers.addListener(future -> onStreamHeadersWritten(context, ticket, prepared, future));
    }

    /** Performs the ordered HTTP/1.1-to-WebSocket transition after normal application dispatch. */
    private void writeWebSocketUpgrade(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared) {
        var validation = validateWebSocketUpgrade(prepared);
        if (validation != null) {
            writeWebSocketUpgradeError(context, ticket, prepared, validation.status(), validation.title());
            return;
        }

        var endpoint = prepared.webSocket();
        var request = prepared.webSocketRequest();
        var limits = endpoint.limits();
        final WebSocketServerHandshakerFactory factory;
        final FullHttpRequest handshakeRequest;
        try {
            factory = new WebSocketServerHandshakerFactory(
                    webSocketLocation(request),
                    endpoint.subprotocols().isEmpty() ? null : String.join(",", endpoint.subprotocols()),
                    false,
                    limits.maximumFrameBytes());
            handshakeRequest = toNettyWebSocketRequest(request);
        } catch (RuntimeException failure) {
            writeWebSocketUpgradeError(context, ticket, prepared, 400, "Bad Request");
            return;
        }

        var handshaker = factory.newHandshaker(handshakeRequest);
        if (handshaker == null) {
            ReferenceCountUtil.release(handshakeRequest);
            writeWebSocketUpgradeError(context, ticket, prepared, 426, "Upgrade Required");
            return;
        }

        // A valid upgrade has already stopped normal HTTP intake. Re-arm the channel before
        // writing 101 so its read interest is live before a peer can observe that response. Any
        // malicious HTTP bytes received before the codec swap still meet webSocketUpgradePending
        // and abort; after the swap the dormant session below owns and closes early WS frames.
        context.channel().config().setAutoRead(true);
        context.read();

        final ChannelFuture handshake;
        final WebSocketSessionHandler session;
        try {
            handshake = handshaker.handshake(
                    context.channel(), handshakeRequest, webSocketResponseHeaders(prepared.headers()), context.newPromise());
            // handshaker.handshake synchronously replaces the HTTP codec with WebSocket codecs,
            // but the 101 write may complete before its listener below gets a turn. Install a
            // dormant session now so a peer's first frame cannot fall through the switched
            // decoder into the pipeline tail. It is activated only after the ordered 101 succeeds.
            session = prepareWebSocketSession(context, prepared, limits);
        } catch (RuntimeException failure) {
            ReferenceCountUtil.release(handshakeRequest);
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            abortConnection(context, "WebSocket handshake transport setup failed");
            return;
        }
        ReferenceCountUtil.release(handshakeRequest);
        handshake.addListener(future -> {
            if (!future.isSuccess()) {
                recordTransportFailure(ticket, prepared);
                prepared.abort();
                abortConnection(context, "WebSocket handshake write failed");
                return;
            }
            completeWebSocketUpgrade(context, ticket, prepared, endpoint, session);
        });
    }

    /** Installs a dormant WebSocket frame owner immediately after Netty swaps codecs. */
    private WebSocketSessionHandler prepareWebSocketSession(
            ChannelHandlerContext context,
            PreparedResponse prepared,
            WebSocketLimits limits) {
        var pipeline = context.pipeline();
        removeIfPresent(pipeline, LimitAwareExpectContinueHandler.class);
        removeIfPresent(pipeline, HttpServerExpectContinueHandler.class);
        removeIfPresent(pipeline, HttpObjectAggregator.class);
        var session = new WebSocketSessionHandler(prepared.webSocketRequest(), limits, runtime);
        pipeline.remove(this);
        // Netty leaves the old HttpServerCodec in place until its 101-write listener runs. Put
        // the WebSocket frame owner directly after the newly inserted decoder, before that old
        // codec, so a peer that sends its first frame immediately after observing 101 cannot have
        // it consumed as HTTP while the write-completion listener is still pending.
        if (pipeline.context("wsdecoder") == null) {
            throw new IllegalStateException("WebSocket handshaker did not install a frame decoder");
        }
        pipeline.addAfter("wsdecoder", "waveWebSocketFrameAggregator",
                new WebSocketFrameAggregator(limits.maximumMessageBytes()));
        pipeline.addAfter("waveWebSocketFrameAggregator", "waveWebSocketSession", session);
        session.armHandshakeReads();
        return session;
    }

    /** Completes ordered HTTP bookkeeping and starts application work after the successful 101. */
    private void completeWebSocketUpgrade(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared,
            io.wavejava.wave.api.websocket.WebSocket endpoint,
            WebSocketSessionHandler session) {
        final ConnectionSequencer.WriteAcknowledgement acknowledgement;
        try {
            acknowledgement = sequencer.acknowledgeWritten(ticket);
        } catch (RuntimeException sequencingFailure) {
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            context.close();
            return;
        }
        if (!acknowledgement.released() || acknowledgement.snapshot().budget().pendingResponses() != 0) {
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            context.close();
            return;
        }
        pendingResponses.remove(ticket);
        try {
            prepared.complete();
        } catch (RuntimeException lifecycleFailure) {
            recordTransportFailure(ticket, prepared);
            context.close();
            return;
        }
        responseWriteInProgress = false;
        webSocketUpgradePending = false;
        recordTransportSuccess(ticket, prepared);
        // claimWebSocketUpgrade deliberately stopped HTTP intake before the ordered 101 write.
        // The dispatcher is now removed, so re-arm the dormant session for WebSocket frames and
        // EOF/RST detection rather than relying on a removed handler's HTTP backpressure state.
        session.activateAfterHandshake();
        session.startEndpoint(endpoint);
    }

    private void writeWebSocketUpgradeError(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse upgrade,
            int status,
            String title) {
        upgrade.abort();
        var error = PreparedResponse.error(status, title, HttpVersion.HTTP_1_1, "GET", false);
        final ChannelFuture write;
        try {
            write = ResponseWriteHandler.write(context, error);
        } catch (RuntimeException failure) {
            recordTransportFailure(ticket, error);
            abortConnection(context, "WebSocket upgrade error write setup failed");
            return;
        }
        write.addListener(future -> {
            if (!future.isSuccess()) {
                recordTransportFailure(ticket, error);
                abortConnection(context, "WebSocket upgrade error write failed");
                return;
            }
            finishResponseWrite(context, ticket, error);
        });
    }

    private static UpgradeFailure validateWebSocketUpgrade(PreparedResponse prepared) {
        var request = prepared.webSocketRequest();
        if (prepared.isHeadRequest() || !"GET".equals(request.method().name())) {
            return new UpgradeFailure(400, "Bad Request");
        }
        if (request.version() != io.wavejava.wave.api.http.HttpVersion.HTTP_1_1 || request.hasStreamingBody()
                || request.body().length() != 0) {
            return new UpgradeFailure(400, "Bad Request");
        }
        if (request.authority().isBlank()
                || !headerContainsToken(request.headers(), "Connection", "Upgrade")
                || !headerContainsToken(request.headers(), "Upgrade", "websocket")) {
            return new UpgradeFailure(400, "Bad Request");
        }
        if (!request.headers().all("Sec-WebSocket-Version").equals(java.util.List.of("13"))) {
            return new UpgradeFailure(426, "Upgrade Required");
        }
        if (!validWebSocketKey(request.headers())) {
            return new UpgradeFailure(400, "Bad Request");
        }
        return null;
    }

    private static boolean validWebSocketKey(Headers headers) {
        var keys = headers.all("Sec-WebSocket-Key");
        if (keys.size() != 1) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(keys.getFirst()).length == 16;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean headerContainsToken(Headers headers, String name, String expected) {
        for (var value : headers.all(name)) {
            for (var token : value.split(",")) {
                if (expected.equalsIgnoreCase(token.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String webSocketLocation(Request request) {
        var scheme = "https".equals(request.scheme()) ? "wss" : "ws";
        return scheme + "://" + request.authority() + request.target();
    }

    private static FullHttpRequest toNettyWebSocketRequest(Request request) {
        var nettyRequest = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.GET,
                request.target(),
                io.netty.buffer.Unpooled.EMPTY_BUFFER);
        request.headers().asMap().forEach((name, values) -> values.forEach(value -> nettyRequest.headers().add(name, value)));
        return nettyRequest;
    }

    private static HttpHeaders webSocketResponseHeaders(Headers headers) {
        var outbound = new DefaultHttpHeaders();
        headers.asMap().forEach((name, values) -> {
            if (!isReservedWebSocketHandshakeHeader(name)) {
                values.forEach(value -> outbound.add(name, value));
            }
        });
        return outbound;
    }

    private static boolean isReservedWebSocketHandshakeHeader(String name) {
        return name.equalsIgnoreCase(HttpHeaderNames.CONNECTION.toString())
                || name.equalsIgnoreCase(HttpHeaderNames.UPGRADE.toString())
                || name.equalsIgnoreCase(HttpHeaderNames.CONTENT_LENGTH.toString())
                || name.equalsIgnoreCase(HttpHeaderNames.TRANSFER_ENCODING.toString())
                || name.regionMatches(true, 0, "Sec-WebSocket-", 0, "Sec-WebSocket-".length());
    }

    private static void removeIfPresent(ChannelPipeline pipeline, Class<? extends ChannelHandler> handlerType) {
        if (pipeline.get(handlerType) != null) {
            pipeline.remove(handlerType);
        }
    }

    private record UpgradeFailure(int status, String title) {
    }

    private void onStreamHeadersWritten(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared,
            io.netty.util.concurrent.Future<? super Void> write) {
        if (!write.isSuccess()) {
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            abortConnection(context, "response stream header write failed");
            return;
        }
        if (closing || runtime.isShutdown() || !context.channel().isActive()) {
            recordTransportResult(
                    ticket,
                    prepared,
                    runtime.isShutdown() || closing
                            ? AccessLogEvent.TransportOutcome.CANCELLED
                            : transportOutcomeForClose(closingReason));
            prepared.abort();
            return;
        }
        if (prepared.isHeadRequest()) {
            writeStreamEndForHead(context, ticket, prepared);
            return;
        }

        var bridge = new FlowBridge(
                context,
                prepared,
                limits.maximumOutboundStreamBytesPerConnection(),
                runtime::executeTransportCleanup,
                new FlowBridge.Listener() {
                    @Override
                    public void onItemWritten(int bytes) {
                        var observation = observations.get(ticket);
                        if (observation != null) {
                            observation.streamItemWritten(bytes);
                        }
                    }

                    @Override
                    public void onComplete() {
                        activeStream = null;
                        finishResponseWrite(context, ticket, prepared);
                    }

                    @Override
                    public void onFailure(String reason, Throwable cause) {
                        activeStream = null;
                        recordTransportFailure(ticket, prepared);
                        prepared.abort();
                        abortConnection(context, reason);
                    }
                });
        activeStream = bridge;
        bridge.start();
    }

    private void writeStreamEndForHead(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared) {
        try {
            ResponseWriteHandler.writeStreamEnd(context).addListener(future -> {
                if (!future.isSuccess()) {
                    recordTransportFailure(ticket, prepared);
                    prepared.abort();
                    abortConnection(context, "response stream terminal write failed");
                    return;
                }
                finishResponseWrite(context, ticket, prepared);
            });
        } catch (RuntimeException failure) {
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            abortConnection(context, "response stream terminal write setup failed");
        }
    }

    private void onAggregateWriteCompletion(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared,
            io.netty.util.concurrent.Future<? super Void> write) {
        if (!write.isSuccess()) {
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            abortConnection(context, "response write failed");
            return;
        }
        finishResponseWrite(context, ticket, prepared);
    }

    private void finishResponseWrite(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared) {
        final ConnectionSequencer.WriteAcknowledgement acknowledgement;
        try {
            acknowledgement = sequencer.acknowledgeWritten(ticket);
        } catch (RuntimeException sequencingFailure) {
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            abortConnection(context, "response write acknowledgement failed");
            return;
        }
        if (!acknowledgement.released()) {
            recordTransportFailure(ticket, prepared);
            prepared.abort();
            pendingResponses.remove(ticket);
            cancelStreamDeadline(ticket);
            streamContexts.remove(ticket);
            responseWriteInProgress = false;
            return;
        }

        pendingResponses.remove(ticket);
        cancelStreamDeadline(ticket);
        streamContexts.remove(ticket);
        try {
            prepared.complete();
        } catch (RuntimeException lifecycleFailure) {
            recordTransportFailure(ticket, prepared);
            abortConnection(context, "response lifecycle completion failed");
            return;
        }
        recordTransportSuccess(ticket, prepared);
        applyReadBackpressure(context, acknowledgement.snapshot());
        if (!prepared.keepAlive()) {
            context.close();
            return;
        }
        responseWriteInProgress = false;
        writeNextResponse(context);
    }

    private void applyReadBackpressure(ChannelHandlerContext context, ConnectionSequencer.Snapshot snapshot) {
        var mustPause = snapshot.shouldPauseReads();
        readsPaused = mustPause;
        updateReadState(context);
        if (!mustPause) {
            issuePendingInboundRead(context);
        }
    }

    /**
     * Keeps automatic reads for aggregate requests, but makes a streaming request body pull
     * input only after its Flow subscriber has demanded one item.
     */
    private void updateReadState(ChannelHandlerContext context) {
        var shouldAutoRead = !closing && !terminalInputPaused && !readsPaused && activeInbound == null;
        if (context.channel().config().isAutoRead() != shouldAutoRead) {
            context.channel().config().setAutoRead(shouldAutoRead);
        }
        if (shouldAutoRead && context.channel().isActive()) {
            context.read();
        }
    }

    private void writeTerminalError(ChannelHandlerContext context, int status, String title) {
        closeOutstanding(context, "invalid oversized request");
        var response = PreparedResponse.error(status, title, HttpVersion.HTTP_1_1, "", false);
        try {
            ResponseWriteHandler.write(context, response).addListener(ignored -> context.close());
        } catch (RuntimeException failure) {
            context.close();
        }
    }

    private void abortConnection(ChannelHandlerContext context, String reason) {
        closeOutstanding(context, reason);
        if (context.channel().isOpen()) {
            context.close();
        }
    }

    private void closeOutstanding(ChannelHandlerContext context, String reason) {
        if (closing) {
            return;
        }
        closing = true;
        closingReason = reason;
        readsPaused = true;
        context.channel().config().setAutoRead(false);

        for (var invocation : new ArrayList<>(activeInvocations.values())) {
            runtime.cancel(invocation, reason);
        }
        activeInvocations.clear();
        for (var requestContext : streamContexts.values()) {
            requestContext.cancellationToken().cancel(reason);
        }
        streamContexts.clear();
        for (var requestContext : inboundContexts.values()) {
            requestContext.cancellationToken().cancel(reason);
        }
        inboundContexts.clear();
        var inboundToCancel = new HashSet<>(inboundStreams.values());
        inboundToCancel.addAll(terminalInboundStreams.values());
        inboundStreams.clear();
        terminalInboundStreams.clear();
        inboundResponseCommitted.clear();
        activeInbound = null;
        inboundReadPending = false;
        for (var bridge : inboundToCancel) {
            bridge.cancel(reason);
        }
        for (var deadline : streamDeadlines.values()) {
            deadline.cancel(false);
        }
        streamDeadlines.clear();
        if (activeStream != null) {
            activeStream.cancel();
            activeStream = null;
        }
        recordOutstandingTermination(reason);
        sequencer.abort();
        pendingResponses.values().forEach(PreparedResponse::abort);
        pendingResponses.clear();
        writeQueue.clear();
        responseWriteInProgress = false;
    }

    /** Keeps a request deadline alive after a handler has committed a long-running stream. */
    private void scheduleStreamDeadline(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            RequestContext requestContext) {
        requestContext.deadline().ifPresent(deadline -> {
            var remaining = deadline.remaining();
            final long delayNanos;
            try {
                delayNanos = remaining.toNanos();
            } catch (ArithmeticException ignored) {
                // Netty accepts a saturated delay and this deadline is practically unbounded.
                streamDeadlines.put(ticket, context.executor().schedule(
                        () -> expireStreamDeadline(context, ticket, requestContext),
                        Long.MAX_VALUE,
                        TimeUnit.NANOSECONDS));
                return;
            }
            streamDeadlines.put(ticket, context.executor().schedule(
                    () -> expireStreamDeadline(context, ticket, requestContext),
                    Math.max(0, delayNanos),
                    TimeUnit.NANOSECONDS));
        });
    }

    private void expireStreamDeadline(
            ChannelHandlerContext context,
            ConnectionSequencer.Ticket ticket,
            RequestContext requestContext) {
        if (closing || !streamContexts.containsKey(ticket)) {
            return;
        }
        requestContext.cancellationToken().cancel(DEADLINE_EXCEEDED);
        abortConnection(context, DEADLINE_EXCEEDED);
    }

    private void cancelStreamDeadline(ConnectionSequencer.Ticket ticket) {
        var deadline = streamDeadlines.remove(ticket);
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    /** Emits exactly one immutable completion event for a successfully acknowledged response. */
    private void recordTransportSuccess(ConnectionSequencer.Ticket ticket, PreparedResponse prepared) {
        recordTransportResult(ticket, prepared, AccessLogEvent.TransportOutcome.WRITTEN);
    }

    /** Emits exactly one immutable completion event for a write or sequencing failure. */
    private void recordTransportFailure(ConnectionSequencer.Ticket ticket, PreparedResponse prepared) {
        recordTransportResult(ticket, prepared, AccessLogEvent.TransportOutcome.FAILED);
    }

    private void recordTransportResult(
            ConnectionSequencer.Ticket ticket,
            PreparedResponse prepared,
            AccessLogEvent.TransportOutcome transportOutcome) {
        var observation = observations.remove(ticket);
        if (observation == null) {
            return;
        }
        observation.transportResponse(prepared);
        observability.record(observation.toEvent(transportOutcome));
    }

    /** Terminates all still-pending application observations when a connection becomes unusable. */
    private void recordOutstandingTermination(String reason) {
        var transportOutcome = transportOutcomeForClose(reason);
        for (var observation : new ArrayList<>(observations.values())) {
            observation.cancel(reason, transportOutcome);
            observability.record(observation.toEvent(transportOutcome));
        }
        observations.clear();
    }

    private static AccessLogEvent.TransportOutcome transportOutcomeForClose(String reason) {
        return switch (reason) {
            case "client disconnected", SERVER_SHUTDOWN, DEADLINE_EXCEEDED, "request body subscription cancelled" ->
                    AccessLogEvent.TransportOutcome.CANCELLED;
            default -> AccessLogEvent.TransportOutcome.FAILED;
        };
    }

    private static io.wavejava.wave.api.http.HttpVersion toWaveVersion(HttpVersion version) {
        if (HttpVersion.HTTP_1_0.equals(version)) {
            return io.wavejava.wave.api.http.HttpVersion.HTTP_1_0;
        }
        if (HttpVersion.HTTP_1_1.equals(version)) {
            return io.wavejava.wave.api.http.HttpVersion.HTTP_1_1;
        }
        throw new IllegalArgumentException("HTTP/2 is not supported in 0.2");
    }

    /** The one wire request body currently accepting manually demanded HTTP content. */
    private record StreamingInbound(ConnectionSequencer.Ticket ticket, InboundFlowBridge bridge) {
    }

    /** EventLoop-confined bounded snapshot; it never retains a request, response, body, or cause. */
    private static final class RequestObservation {
        private final RequestContext context;
        private final String method;
        private final Instant startedAt = Instant.now();
        private final long startedAtNanos = System.nanoTime();
        private volatile String route = "<middleware>";
        private int status;
        private long responseBodyBytes;
        private Outcome.Kind applicationOutcome = Outcome.Kind.CLIENT_CANCELLATION;
        private boolean applicationCompleted;

        private RequestObservation(RequestContext context, String method) {
            this.context = Objects.requireNonNull(context, "context");
            this.method = Objects.requireNonNull(method, "method");
        }

        private String route() {
            return route;
        }

        private void routeObserved(String route) {
            this.route = Objects.requireNonNull(route, "route");
        }

        private void applicationCompleted(PreparedResponse prepared) {
            applicationCompleted = true;
            route = prepared.routePattern();
            status = prepared.status();
            responseBodyBytes = prepared.responseBodyBytes();
            if (prepared.isStreaming()) {
                responseBodyBytes = 0;
            }
            applicationOutcome = prepared.applicationOutcome().kind();
        }

        private void streamItemWritten(int bytes) {
            if (bytes <= 0 || responseBodyBytes < 0) {
                return;
            }
            responseBodyBytes = Long.MAX_VALUE - responseBodyBytes < bytes
                    ? Long.MAX_VALUE
                    : responseBodyBytes + bytes;
        }

        private void transportResponse(PreparedResponse prepared) {
            status = prepared.status();
            if (!applicationCompleted) {
                applicationCompleted(prepared);
                return;
            }
            if (!prepared.isStreaming()) {
                responseBodyBytes = prepared.responseBodyBytes();
            }
        }

        private void cancel(String reason, AccessLogEvent.TransportOutcome transportOutcome) {
            if (applicationCompleted) {
                return;
            }
            var cancellationReason = context.cancellationReason().orElse(reason);
            applicationOutcome = DEADLINE_EXCEEDED.equals(cancellationReason)
                    ? Outcome.Kind.DEADLINE_EXCEEDED
                    : transportOutcome == AccessLogEvent.TransportOutcome.CANCELLED
                            ? Outcome.Kind.CLIENT_CANCELLATION
                            : Outcome.Kind.TRANSPORT_FAILURE;
        }

        private AccessLogEvent toEvent(AccessLogEvent.TransportOutcome transportOutcome) {
            var elapsedNanos = System.nanoTime() - startedAtNanos;
            var latency = elapsedNanos <= 0 ? Duration.ZERO : Duration.ofNanos(elapsedNanos);
            return new AccessLogEvent(
                    context.requestId(),
                    method,
                    route,
                    status,
                    startedAt,
                    latency,
                    responseBodyBytes,
                    applicationOutcome,
                    transportOutcome);
        }
    }
}

