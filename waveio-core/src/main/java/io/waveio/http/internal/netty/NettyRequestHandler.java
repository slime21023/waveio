package io.waveio.http.internal.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.internal.body.InboundBodyPublisher;
import io.waveio.http.internal.dispatch.RequestDispatcher;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.Router;
import io.waveio.http.server.ExceptionHandler;
import io.waveio.http.server.RequestObservation;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Coordinates one connection: it demultiplexes inbound framing results into exchanges, drives
 * dispatch and head-of-line response writing, and tears the connection down.
 *
 * <p>Ordering, transport flow control, interim responses and per-exchange lifecycle each live in
 * their own type; this class owns only the wiring between them and the channel event loop.
 */
final class NettyRequestHandler extends SimpleChannelInboundHandler<Object> {
    private static final System.Logger LOG =
            System.getLogger(NettyRequestHandler.class.getName());

    private final NettyRequestAdapter requestAdapter;
    private final NettyResponseWriter responseWriter;
    private final Router router;
    private final RequestDispatcher dispatcher;
    private final ConnectionOptions options;
    private final ExchangeQueue queue;
    private final AutoReadGate autoRead = new AutoReadGate();
    private final ContinueScheduler continueScheduler = new ContinueScheduler();
    private boolean writing;
    private boolean connectionClosed;
    private NettyResponseWriter.WriteHandle activeWrite;

    NettyRequestHandler(Router router, List<Middleware> middleware,
            ExceptionHandler exceptionHandler, ExecutorService blockingExecutor) {
        this(router, middleware, exceptionHandler, blockingExecutor, ConnectionOptions.defaults());
    }

    NettyRequestHandler(Router router, List<Middleware> middleware,
            ExceptionHandler exceptionHandler, ExecutorService blockingExecutor,
            ConnectionOptions options) {
        this.router = router;
        this.options = options;
        dispatcher = new RequestDispatcher(router, middleware, exceptionHandler, blockingExecutor);
        requestAdapter = new NettyRequestAdapter(options.bodyCodec());
        queue = new ExchangeQueue(options.maxPendingRequests());
        responseWriter = new NettyResponseWriter(options.responseStallTimeout(),
                options.bodyCodec());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Object message) {
        if (connectionClosed) return;
        if (message instanceof NettyInboundMessage.Buffered buffered) {
            enqueue(context, buffered.request(), buffered.keepAlive(), null, true, null);
        } else if (message instanceof NettyInboundMessage.StreamingStart streaming) {
            enqueue(context, streaming.request(), streaming.keepAlive(), streaming.body(),
                    false, null);
        } else if (message instanceof NettyInboundMessage.StreamingComplete complete) {
            completeStreamingBody(context, complete.body());
        } else if (message instanceof NettyInboundMessage.StreamingFailure failure) {
            failStreamingBody(context, failure.body(), failure.failure());
        } else if (message instanceof NettyInboundMessage.Rejected rejected) {
            enqueue(context, rejected.request(), false, null, true, rejected.response());
        } else if (message instanceof NettyInboundMessage.ContinueExpected) {
            continueScheduler.expect(queue.tail());
            continueScheduler.flushIfReady(context, queue, writing);
        } else if (message instanceof FullHttpRequest nettyRequest) {
            try {
                enqueue(context, requestAdapter.adapt(context, nettyRequest),
                        HttpUtil.isKeepAlive(nettyRequest), null, true, null);
            } catch (IllegalArgumentException failure) {
                enqueue(context, requestAdapter.rejected(context, nettyRequest), false,
                        null, true, badRequest(failure.getMessage()));
            }
        } else {
            context.fireChannelRead(message);
        }
    }

    private void enqueue(ChannelHandlerContext context, HttpRequest request, boolean keepAlive,
            InboundBodyPublisher body, boolean bodyComplete, HttpResponse immediateResponse) {
        if (queue.overflowed()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Closing connection from {0}: more pipelined requests arrived in one read"
                    + " than the {1} pending-request bound allows",
                    context.channel().remoteAddress(), options.maxPendingRequests());
            closeConnection(context);
            return;
        }
        var exchange = new Exchange(request, keepAlive, router.match(request), body, bodyComplete);
        if (immediateResponse != null) {
            exchange.claim();
            exchange.respondWith(immediateResponse);
        }
        boolean wasIdle = queue.isEmpty();
        queue.add(exchange);
        // A dispatched exchange owns the connection even while its request body is still arriving;
        // from here the handler deadline, not the read deadline, bounds the work.
        if (wasIdle) requestStarted(context);
        syncAutoRead(context);
        if (immediateResponse == null && (queue.head() == exchange || exchange.asynchronous())) {
            start(context, exchange);
        }
        tryWrite(context);
    }

    private void completeStreamingBody(ChannelHandlerContext context,
            InboundBodyPublisher publisher) {
        var exchange = queue.withBody(publisher);
        if (exchange == null) return;
        exchange.bodyCompleted();
        syncAutoRead(context);
        tryWrite(context);
    }

    private void failStreamingBody(ChannelHandlerContext context, InboundBodyPublisher publisher,
            Throwable failure) {
        var exchange = queue.withBody(publisher);
        if (exchange == null || !exchange.claim()) return;
        exchange.cancelDispatch();
        exchange.recordFailure(failure);
        exchange.discardBody();
        exchange.respondWith(HttpResponse.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body("Payload Too Large"));
        tryWrite(context);
    }

    /** Derives read suspension from queue state so no single reason can restore reads alone. */
    private void syncAutoRead(ChannelHandlerContext context) {
        autoRead.set(context, AutoReadGate.Reason.PENDING_LIMIT, queue.overflowed());
        autoRead.set(context, AutoReadGate.Reason.INBOUND_STREAM, queue.hasIncomingBody());
    }

    private void start(ChannelHandlerContext context, Exchange exchange) {
        if (exchange.started() || connectionClosed) return;
        exchange.beginDispatch(options.handlerTimeout());
        exchange.scheduleTimeout(context.executor().schedule(() -> timeout(context, exchange),
                ConnectionOptions.nanos(options.handlerTimeout()), TimeUnit.NANOSECONDS));
        Consumer<HttpResponse> completion = response -> executeOnEventLoop(context,
                () -> finishDispatch(context, exchange, response));
        var body = exchange.bodyPublisher();
        var dispatch = body == null
                ? dispatcher.dispatch(exchange.request(), completion, exchange::recordFailure)
                : dispatcher.dispatchStreaming(exchange.request(), body, completion,
                        exchange::recordFailure);
        exchange.attach(dispatch);
    }

    private void finishDispatch(ChannelHandlerContext context, Exchange exchange,
            HttpResponse response) {
        if (exchange.expired()) {
            timeout(context, exchange);
            return;
        }
        if (!exchange.claim()) return;
        exchange.respondWith(response);
        exchange.abandonBody(new InboundBodyPublisher.BodyNotConsumedException());
        tryWrite(context);
    }

    private void timeout(ChannelHandlerContext context, Exchange exchange) {
        if (!exchange.claim()) return;
        exchange.cancelDispatch();
        var failure = new TimeoutException("HTTP handler exceeded " + options.handlerTimeout());
        exchange.recordFailure(failure);
        exchange.markTimedOut();
        exchange.abandonBody(failure);
        exchange.respondWith(dispatcher.failureResponse(failure, exchange.request()));
        tryWrite(context);
    }

    private void tryWrite(ChannelHandlerContext context) {
        if (writing || connectionClosed || !context.channel().isActive()) return;
        var exchange = queue.head();
        if (exchange == null) return;
        if (!exchange.started() && exchange.response() == null) start(context, exchange);
        if (queue.head() != exchange || writing || !exchange.readyToWrite()) return;

        writing = true;
        exchange.beginWrite();
        var handle = new NettyResponseWriter.WriteHandle();
        activeWrite = handle;
        boolean keepAlive = exchange.keepAlive();
        responseWriter.write(context, exchange.request().method(), exchange.response(), keepAlive,
                result -> onWriteResult(context, exchange, handle, keepAlive, result), handle);
    }

    private void onWriteResult(ChannelHandlerContext context, Exchange exchange,
            NettyResponseWriter.WriteHandle handle, boolean keepAlive, WriteResult result) {
        if (activeWrite == handle) activeWrite = null;
        if (connectionClosed) return;
        if (result instanceof WriteResult.Failure failure) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "Closing connection from " + context.channel().remoteAddress()
                            + ": writing the response failed", failure.cause());
            exchange.recordFailure(failure.cause());
            closeConnection(context);
            return;
        }
        exchange.finishWrite();
        exchange.observe(options.observers(), exchange.outcome(),
                Optional.of(exchange.response().status()));
        if (queue.removeHead() != exchange) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Closing connection from {0}: the exchange queue head changed during a write",
                    context.channel().remoteAddress());
            closeConnection(context);
            return;
        }
        writing = false;
        if (queue.isEmpty()) requestFinished(context);
        if (!keepAlive || !context.channel().isActive()) {
            cancelAll();
            queue.clear();
            return;
        }
        syncAutoRead(context);
        continueScheduler.flushIfReady(context, queue, writing);
        tryWrite(context);
    }

    private static void executeOnEventLoop(ChannelHandlerContext context, Runnable action) {
        if (context.executor().inEventLoop()) action.run();
        else context.executor().execute(action);
    }

    private static void requestStarted(ChannelHandlerContext context) {
        var idle = context.pipeline().get(ConnectionIdleHandler.class);
        if (idle != null) idle.requestStarted(context);
    }

    private static void requestFinished(ChannelHandlerContext context) {
        var idle = context.pipeline().get(ConnectionIdleHandler.class);
        if (idle != null) idle.requestFinished(context);
    }

    private static HttpResponse badRequest(String message) {
        return HttpResponse.status(HttpStatus.BAD_REQUEST)
                .header("content-type", "text/plain; charset=utf-8")
                .body(message == null ? "Bad Request" : message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
        LOG.log(System.Logger.Level.DEBUG,
                () -> "Closing connection from " + context.channel().remoteAddress()
                        + ": an exception reached the end of the pipeline", failure);
        closeConnection(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        closeState(context);
        context.fireChannelInactive();
    }

    private void closeConnection(ChannelHandlerContext context) {
        closeState(context);
        context.close();
    }

    private void closeState(ChannelHandlerContext context) {
        if (connectionClosed) return;
        connectionClosed = true;
        continueScheduler.clear();
        autoRead.set(context, AutoReadGate.Reason.CLOSED, true);
        var write = activeWrite;
        if (write != null) write.cancel();
        queue.forEach(exchange -> exchange.observe(options.observers(),
                RequestObservation.Outcome.DISCONNECTED, Optional.empty()));
        queue.forEach(Exchange::disconnected);
        cancelAll();
        queue.clear();
    }

    private void cancelAll() {
        queue.forEach(Exchange::cancel);
    }
}
