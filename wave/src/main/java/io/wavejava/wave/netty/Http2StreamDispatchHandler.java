package io.wavejava.wave.netty;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.wavejava.wave.api.http.Body;
import io.wavejava.wave.api.http.Deadline;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.RequestContext;
import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.api.observability.AccessLogEvent;
import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import io.wavejava.wave.api.server.RequestBodyMode;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.runtime.RequestDispatcher;
import io.wavejava.wave.runtime.InboundFlowBridge;
import io.wavejava.wave.runtime.InvocationRuntime;
import io.wavejava.wave.runtime.ObservabilityDispatcher;
import io.wavejava.wave.runtime.RequestInvocation;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded HTTP/2 request/response lifecycle, installed on an individual stream channel. */
final class Http2StreamDispatchHandler extends ChannelInboundHandlerAdapter {
    private static final String DEADLINE_EXCEEDED = "deadline exceeded";
    private static final String SERVER_SHUTDOWN = "server shutdown";

    private final RequestDispatcher application;
    private final InvocationRuntime runtime;
    private final ServerLimits limits;
    private final ServerTimeouts timeouts;
    private final java.util.concurrent.Semaphore inFlightRequests;
    private final Http2ConnectionState connection;
    private final ObservabilityDispatcher observability;
    private final ForwardedHeaderPolicy forwardedHeaders;
    private final RequestBodyMode requestBodyMode;

    private RequestInvocation<PreparedResponse> invocation;
    private RequestContext requestContext;
    private FlowBridge activeStream;
    private io.netty.util.concurrent.ScheduledFuture<?> streamDeadline;
    private Observation observation;
    private boolean receivedRequest;
    private boolean streamAdmitted;
    private boolean responseTerminal;
    private InboundFlowBridge inboundBody;
    private boolean inboundEnded;
    private boolean inboundReadPending;

    Http2StreamDispatchHandler(
            RequestDispatcher application,
            InvocationRuntime runtime,
            ServerLimits limits,
            ServerTimeouts timeouts,
            java.util.concurrent.Semaphore inFlightRequests,
            Http2ConnectionState connection,
            ObservabilityDispatcher observability,
            ForwardedHeaderPolicy forwardedHeaders,
            RequestBodyMode requestBodyMode) {
        this.application = Objects.requireNonNull(application, "application");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.inFlightRequests = Objects.requireNonNull(inFlightRequests, "inFlightRequests");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.forwardedHeaders = Objects.requireNonNull(forwardedHeaders, "forwardedHeaders");
        this.requestBodyMode = Objects.requireNonNull(requestBodyMode, "requestBodyMode");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        if (requestBodyMode == RequestBodyMode.STREAMING) {
            // A child HTTP/2 stream owns its own read demand. Keeping auto-read off means an
            // unconsumed Flow body remains governed by HTTP/2 flow control rather than becoming
            // an unbounded Java-side queue of HttpContent objects.
            context.channel().config().setAutoRead(false);
            context.read();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        try {
            if (requestBodyMode == RequestBodyMode.STREAMING) {
                handleStreamingMessage(context, message);
                return;
            }
            if (!(message instanceof FullHttpRequest inbound) || receivedRequest) {
                writeTerminal(context, PreparedResponse.error(
                        400, "Bad Request", HttpVersion.HTTP_1_1, "GET", true));
                return;
            }
            receivedRequest = true;
            handle(context, inbound);
        } finally {
            ReferenceCountUtil.release(message);
        }
    }

    private void handle(ChannelHandlerContext context, FullHttpRequest inbound) {
        if (inbound.decoderResult().isFailure() || !hasSafeHttp2Headers(inbound)) {
            writeTerminal(context, PreparedResponse.error(
                    400, "Bad Request", HttpVersion.HTTP_1_1, inbound.method().name(), true));
            return;
        }
        if (!connection.tryAcquireStream()) {
            writeTerminal(context, PreparedResponse.error(
                    503, "Service Unavailable", HttpVersion.HTTP_1_1, inbound.method().name(), true));
            return;
        }
        streamAdmitted = true;
        final Request request;
        try {
            request = toRequest(context, inbound);
        } catch (RuntimeException failure) {
            writeTerminal(context, PreparedResponse.error(
                    400, "Bad Request", HttpVersion.HTTP_1_1, inbound.method().name(), true));
            return;
        }
        dispatch(context, request, inbound.method().name());
    }

    /** Handles one Flow-backed request head and waits for the body only under subscriber demand. */
    private void handleStreamingMessage(ChannelHandlerContext context, Object message) {
        if (message instanceof FullHttpRequest inbound) {
            handleStreamingRequest(context, inbound);
            if (!responseTerminal && inboundBody != null && !inboundEnded) {
                offerStreamingContent(context, inbound, true);
            }
            return;
        }
        if (message instanceof HttpRequest inbound) {
            handleStreamingRequest(context, inbound);
            return;
        }
        if (message instanceof HttpContent inbound) {
            offerStreamingContent(context, inbound, inbound instanceof LastHttpContent);
            return;
        }
        writeTerminal(context, PreparedResponse.error(400, "Bad Request", HttpVersion.HTTP_1_1, "GET", true));
    }

    private void handleStreamingRequest(ChannelHandlerContext context, HttpRequest inbound) {
        if (receivedRequest) {
            writeTerminal(context, PreparedResponse.error(
                    400, "Bad Request", HttpVersion.HTTP_1_1, inbound.method().name(), true));
            return;
        }
        receivedRequest = true;
        if (inbound.decoderResult().isFailure() || !hasSafeHttp2Headers(inbound)) {
            writeTerminal(context, PreparedResponse.error(
                    400, "Bad Request", HttpVersion.HTTP_1_1, inbound.method().name(), true));
            return;
        }
        if (!connection.tryAcquireStream()) {
            writeTerminal(context, PreparedResponse.error(
                    503, "Service Unavailable", HttpVersion.HTTP_1_1, inbound.method().name(), true));
            return;
        }
        streamAdmitted = true;

        var bridgeReference = new AtomicReference<InboundFlowBridge>();
        var bridge = new InboundFlowBridge(
                runtime::executeTransportCleanup,
                connection.maximumAggregateRequestBodyBytes(),
                new InboundFlowBridge.Listener() {
                    @Override
                    public void onDemandAvailable() {
                        scheduleOnEventLoop(context, () -> requestInboundRead(context, bridgeReference.get()));
                    }

                    @Override
                    public void onComplete() {
                        // The stream's terminal input frame has already released its copied bytes.
                    }

                    @Override
                    public void onFailure(String reason, Throwable cause) {
                        scheduleOnEventLoop(context, () -> failInboundBody(context, bridgeReference.get(), reason));
                    }

                    @Override
                    public void onCancelled() {
                        scheduleOnEventLoop(context, () -> cancelInboundBody(context, bridgeReference.get()));
                    }
                });
        bridgeReference.set(bridge);
        final Request request;
        try {
            request = toStreamingRequest(context, inbound, bridge);
        } catch (RuntimeException failure) {
            writeTerminal(context, PreparedResponse.error(
                    400, "Bad Request", HttpVersion.HTTP_1_1, inbound.method().name(), true));
            return;
        }
        inboundBody = bridge;
        dispatch(context, request, inbound.method().name());
    }

    private void offerStreamingContent(ChannelHandlerContext context, HttpContent inbound, boolean terminal) {
        var bridge = inboundBody;
        if (bridge == null || inboundEnded) {
            context.close();
            return;
        }
        var bytes = new byte[inbound.content().readableBytes()];
        inbound.content().getBytes(inbound.content().readerIndex(), bytes);
        if (!bridge.offer(bytes, terminal)) {
            return;
        }
        if (terminal) {
            inboundEnded = true;
            inboundReadPending = false;
        }
    }

    private void requestInboundRead(ChannelHandlerContext context, InboundFlowBridge bridge) {
        if (bridge == null || bridge != inboundBody || inboundEnded || !context.channel().isActive()) {
            return;
        }
        inboundReadPending = true;
        context.read();
    }

    private void failInboundBody(ChannelHandlerContext context, InboundFlowBridge bridge, String reason) {
        if (bridge == null || bridge != inboundBody) {
            return;
        }
        if (requestContext != null) {
            requestContext.cancellationToken().cancel(reason);
        }
        if (invocation != null && !invocation.isDone()) {
            runtime.cancel(invocation, reason);
        }
        if (!responseTerminal) {
            writeTerminal(context, PreparedResponse.error(
                    413,
                    "Payload Too Large",
                    HttpVersion.HTTP_1_1,
                    invocation == null ? "GET" : invocation.request().method().name(),
                    true));
            return;
        }
        context.close();
    }

    private void cancelInboundBody(ChannelHandlerContext context, InboundFlowBridge bridge) {
        if (bridge == null || bridge != inboundBody || !context.channel().isActive()) {
            return;
        }
        if (requestContext != null) {
            requestContext.cancellationToken().cancel("request body subscription cancelled");
        }
        if (invocation != null && !invocation.isDone()) {
            runtime.cancel(invocation, "request body subscription cancelled");
        }
        context.close();
    }

    private void dispatch(ChannelHandlerContext context, Request request, String requestMethod) {
        if (!inFlightRequests.tryAcquire()) {
            writeTerminal(context, PreparedResponse.error(
                    503, "Service Unavailable", HttpVersion.HTTP_1_1, requestMethod, true));
            return;
        }
        requestContext = RequestContext.builder(UUID.randomUUID().toString())
                .deadline(Deadline.after(timeouts.requestTimeout()))
                .build();
        observation = new Observation(requestContext, request.method().name());
        try {
            invocation = runtime.submit(request, requestContext, invocationRequest -> {
                var dispatch = application.dispatch(invocationRequest, observation::routeObserved);
                return PreparedResponse.render(
                        dispatch.response(),
                        HttpVersion.HTTP_1_1,
                        invocationRequest.method().name(),
                        true,
                        invocationRequest,
                        dispatch.outcome(),
                        dispatch.routePattern());
            });
            invocation.completion().whenComplete((prepared, failure) -> {
                inFlightRequests.release();
                scheduleOnEventLoop(context, () -> completeInvocation(context, prepared, failure));
            });
        } catch (RejectedExecutionException rejected) {
            inFlightRequests.release();
            writeTerminal(context, PreparedResponse.error(
                    503, "Service Unavailable", HttpVersion.HTTP_1_1, requestMethod, true));
        }
    }

    private Request toStreamingRequest(
            ChannelHandlerContext context, HttpRequest inbound, InboundFlowBridge bodyPublisher) {
        var target = inbound.uri();
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("HTTP/2 request target must be origin-form");
        }
        var headers = toWaveHeaders(inbound);
        SocketAddress remote = context.channel().parent().remoteAddress();
        var scheme = scheme(context, inbound);
        var request = Request.builder()
                .method(io.wavejava.wave.api.http.HttpMethod.of(inbound.method().name()))
                .version(io.wavejava.wave.api.http.HttpVersion.HTTP_2)
                .scheme(scheme)
                .target(target)
                .headers(headers)
                .bodyPublisher(bodyPublisher)
                .remoteAddress(remote)
                .build();
        return forwardedHeaders.apply(request);
    }

    private Request toRequest(ChannelHandlerContext context, FullHttpRequest inbound) {
        var target = inbound.uri();
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("HTTP/2 request target must be origin-form");
        }
        var headers = toWaveHeaders(inbound);
        var content = new byte[inbound.content().readableBytes()];
        inbound.content().getBytes(inbound.content().readerIndex(), content);
        SocketAddress remote = context.channel().parent().remoteAddress();
        var scheme = scheme(context, inbound);
        var request = Request.builder()
                .method(io.wavejava.wave.api.http.HttpMethod.of(inbound.method().name()))
                .version(io.wavejava.wave.api.http.HttpVersion.HTTP_2)
                .scheme(scheme)
                .target(target)
                .headers(headers)
                // The HTTP/2 aggregate path uses the same static connection-to-stream
                // partition as the aggregator and Flow bridge. Using the global HTTP/1 body
                // limit here would make a decoded full request bypass the parent H2 budget.
                .body(Body.of(content, connection.maximumAggregateRequestBodyBytes()))
                .remoteAddress(remote)
                .build();
        return forwardedHeaders.apply(request);
    }

    private static Headers toWaveHeaders(HttpRequest inbound) {
        var headers = Headers.builder();
        for (Map.Entry<String, String> entry : inbound.headers()) {
            if (!isInternalHttp2Header(entry.getKey())) {
                headers.add(entry.getKey(), entry.getValue());
            }
        }
        return headers.build();
    }

    private static String scheme(ChannelHandlerContext context, HttpRequest inbound) {
        var parentPipeline = context.channel().parent().pipeline();
        var convertedScheme = inbound.headers().get(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text());
        return convertedScheme == null
                ? (parentPipeline.get(SslHandler.class) == null ? "http" : "https")
                : convertedScheme.toString();
    }

    private void completeInvocation(ChannelHandlerContext context, PreparedResponse prepared, Throwable failure) {
        if (!context.channel().isActive()) {
            if (prepared != null) {
                prepared.abort();
            }
            emitCancellation("client disconnected");
            return;
        }
        var completed = failure == null ? prepared : responseForFailure(failure);
        if (completed == null) {
            emitFailure();
            context.close();
            return;
        }
        if (completed.isWebSocket()) {
            // RFC 8441 extended CONNECT is intentionally outside this Wave 1.0 HTTP/2 baseline.
            completed.abort();
            completed = PreparedResponse.error(
                    501,
                    "Not Implemented",
                    HttpVersion.HTTP_1_1,
                    invocation.request().method().name(),
                    true,
                    completed.applicationOutcome(),
                    completed.routePattern());
        }
        observation.applicationCompleted(completed);
        if (completed.isStreaming()) {
            writeStream(context, completed);
        } else {
            writeAggregate(context, completed);
        }
    }

    private PreparedResponse responseForFailure(Throwable failure) {
        var reason = requestContext == null ? "" : requestContext.cancellationReason().orElse("");
        var method = invocation == null ? "GET" : invocation.request().method().name();
        var route = observation == null ? "<pending>" : observation.route;
        if (DEADLINE_EXCEEDED.equals(reason)) {
            return PreparedResponse.error(504, "Gateway Timeout", HttpVersion.HTTP_1_1, method, true,
                    Outcome.failure(Outcome.Kind.DEADLINE_EXCEEDED, failure), route);
        }
        if (SERVER_SHUTDOWN.equals(reason)) {
            return PreparedResponse.error(503, "Service Unavailable", HttpVersion.HTTP_1_1, method, true,
                    Outcome.failure(Outcome.Kind.CLIENT_CANCELLATION, failure), route);
        }
        if (!reason.isBlank()) {
            return PreparedResponse.error(500, "Internal Server Error", HttpVersion.HTTP_1_1, method, true,
                    Outcome.failure(Outcome.Kind.CLIENT_CANCELLATION, failure), route);
        }
        return PreparedResponse.error(500, "Internal Server Error", HttpVersion.HTTP_1_1, method, true,
                Outcome.failure(Outcome.Kind.APPLICATION_FAILURE, failure), route);
    }

    private void writeAggregate(ChannelHandlerContext context, PreparedResponse prepared) {
        final ChannelFuture write;
        try {
            write = context.writeAndFlush(prepared.toNettyHttp2Response());
        } catch (RuntimeException failure) {
            prepared.abort();
            emitFailure();
            context.close();
            return;
        }
        write.addListener(future -> {
            if (!future.isSuccess()) {
                prepared.abort();
                emitFailure();
                context.close();
                return;
            }
            prepared.complete();
            emitWritten();
        });
    }

    private void writeStream(ChannelHandlerContext context, PreparedResponse prepared) {
        final ChannelFuture headers;
        try {
            headers = context.writeAndFlush(prepared.toNettyHttp2StreamHeaders());
        } catch (RuntimeException failure) {
            prepared.abort();
            emitFailure();
            context.close();
            return;
        }
        headers.addListener(future -> {
            if (!future.isSuccess()) {
                prepared.abort();
                emitFailure();
                context.close();
                return;
            }
            if (prepared.isHeadRequest()) {
                context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(end -> {
                    if (end.isSuccess()) {
                        prepared.complete();
                        emitWritten();
                    } else {
                        prepared.abort();
                        emitFailure();
                        context.close();
                    }
                });
                return;
            }
            activeStream = new FlowBridge(
                    context,
                    prepared,
                    connection.outboundBytesPerStream(),
                    runtime::execute,
                    new FlowBridge.Listener() {
                        @Override
                        public void onItemWritten(int bytes) {
                            if (observation != null) {
                                observation.streamItemWritten(bytes);
                            }
                        }

                        @Override
                        public void onComplete() {
                            cancelStreamDeadline();
                            prepared.complete();
                            emitWritten();
                        }

                        @Override
                        public void onFailure(String reason, Throwable cause) {
                            cancelStreamDeadline();
                            prepared.abort();
                            emitFailure();
                            context.close();
                        }
                    });
            scheduleStreamDeadline(context);
            activeStream.start();
        });
    }

    private void scheduleStreamDeadline(ChannelHandlerContext context) {
        if (requestContext == null) {
            return;
        }
        var delay = requestContext.deadline().orElseThrow().remaining(Instant.now());
        streamDeadline = context.executor().schedule(() -> {
            if (responseTerminal || activeStream == null) {
                return;
            }
            requestContext.cancellationToken().cancel(DEADLINE_EXCEEDED);
            activeStream.cancel();
            emitCancellation(DEADLINE_EXCEEDED);
            context.close();
        }, toNanosSaturated(delay), TimeUnit.NANOSECONDS);
    }

    private void cancelStreamDeadline() {
        var deadline = streamDeadline;
        streamDeadline = null;
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        cancelStreamDeadline();
        if (activeStream != null) {
            activeStream.cancel();
        }
        if (inboundBody != null && !inboundBody.isSourceCompleted()) {
            inboundBody.cancel("client disconnected");
        }
        if (invocation != null && !invocation.isDone()) {
            runtime.cancel(invocation, "client disconnected");
        }
        emitCancellation(requestContext == null
                ? "client disconnected"
                : requestContext.cancellationReason().orElse("client disconnected"));
        releaseStreamAdmission();
        context.fireChannelInactive();
    }

    /**
     * Converts codec/aggregator failures into the ordinary stream-close path instead of allowing
     * a malformed or reset stream to reach Netty's pipeline tail. {@link #channelInactive} owns
     * cancellation, invocation interruption, and stream-admission release exactly once.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
        context.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof Http2ResetFrame reset) {
            var reason = "HTTP/2 stream reset (" + reset.errorCode() + ')';
            cancelStreamDeadline();
            if (activeStream != null) {
                activeStream.cancel();
            }
            if (inboundBody != null && !inboundBody.isSourceCompleted()) {
                inboundBody.cancel(reason);
            }
            if (invocation != null && !invocation.isDone()) {
                runtime.cancel(invocation, reason);
            }
            emitCancellation(reason);
            context.close();
            return;
        }
        context.fireUserEventTriggered(event);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext context) {
        if (activeStream != null) {
            activeStream.onChannelWritabilityChanged();
        }
        context.fireChannelWritabilityChanged();
    }

    private void writeTerminal(ChannelHandlerContext context, PreparedResponse response) {
        if (responseTerminal || !context.channel().isActive()) {
            return;
        }
        responseTerminal = true;
        context.writeAndFlush(response.toNettyHttp2Response()).addListener(future -> context.close());
    }

    private void emitWritten() {
        emit(AccessLogEvent.TransportOutcome.WRITTEN);
    }

    private void emitFailure() {
        emit(AccessLogEvent.TransportOutcome.FAILED);
    }

    private void emitCancellation(String reason) {
        if (observation != null) {
            observation.cancel(reason);
        }
        emit(AccessLogEvent.TransportOutcome.CANCELLED);
    }

    private void emit(AccessLogEvent.TransportOutcome outcome) {
        if (responseTerminal) {
            return;
        }
        responseTerminal = true;
        if (observation != null) {
            observability.record(observation.toEvent(outcome));
        }
        releaseStreamAdmission();
    }

    private void releaseStreamAdmission() {
        if (streamAdmitted) {
            streamAdmitted = false;
            connection.releaseStream();
        }
    }

    private static boolean hasSafeHttp2Headers(HttpRequest request) {
        var headers = request.headers();
        if (headers.contains(HttpHeaderNames.CONNECTION) || headers.contains(HttpHeaderNames.KEEP_ALIVE)
                || headers.contains(HttpHeaderNames.PROXY_CONNECTION) || headers.contains(HttpHeaderNames.UPGRADE)) {
            return false;
        }
        var transferEncoding = headers.get(HttpHeaderNames.TRANSFER_ENCODING);
        return transferEncoding == null || transferEncoding.equalsIgnoreCase("trailers");
    }

    private static boolean isInternalHttp2Header(String name) {
        for (var header : HttpConversionUtil.ExtensionHeaderNames.values()) {
            if (header.text().contentEqualsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void scheduleOnEventLoop(ChannelHandlerContext context, Runnable action) {
        if (context.executor().inEventLoop()) {
            action.run();
            return;
        }
        try {
            context.executor().execute(action);
        } catch (RejectedExecutionException ignored) {
            // The stream has already reached transport teardown.
        }
    }

    private static long toNanosSaturated(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return 0;
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /** Event-loop-owned snapshot; no request/response/body/cause survives to provider callbacks. */
    private static final class Observation {
        private final RequestContext context;
        private final String method;
        private final Instant startedAt = Instant.now();
        private final long startedNanos = System.nanoTime();
        private volatile String route = "<middleware>";
        private int status;
        private long responseBytes;
        private Outcome.Kind applicationOutcome = Outcome.Kind.CLIENT_CANCELLATION;

        private Observation(RequestContext context, String method) {
            this.context = context;
            this.method = method;
        }

        private void routeObserved(String route) {
            this.route = Objects.requireNonNull(route, "route");
        }

        private void applicationCompleted(PreparedResponse response) {
            route = response.routePattern();
            status = response.status();
            responseBytes = response.isStreaming() ? 0 : response.responseBodyBytes();
            applicationOutcome = response.applicationOutcome().kind();
        }

        private void streamItemWritten(int bytes) {
            if (bytes > 0 && responseBytes >= 0) {
                responseBytes = Long.MAX_VALUE - responseBytes < bytes ? Long.MAX_VALUE : responseBytes + bytes;
            }
        }

        private void cancel(String reason) {
            if (DEADLINE_EXCEEDED.equals(reason)) {
                applicationOutcome = Outcome.Kind.DEADLINE_EXCEEDED;
            } else if (applicationOutcome == Outcome.Kind.CLIENT_CANCELLATION) {
                applicationOutcome = Outcome.Kind.CLIENT_CANCELLATION;
            }
        }

        private AccessLogEvent toEvent(AccessLogEvent.TransportOutcome transportOutcome) {
            var elapsed = System.nanoTime() - startedNanos;
            return new AccessLogEvent(
                    context.requestId(),
                    method,
                    route,
                    status,
                    startedAt,
                    Duration.ofNanos(Math.max(0L, elapsed)),
                    responseBytes,
                    applicationOutcome,
                    transportOutcome);
        }
    }
}

