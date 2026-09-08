package io.waveio.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.waveio.execution.ExecutionConfig;
import io.waveio.execution.ExecutionRuntime;
import io.waveio.http.Context;
import io.waveio.http.Handler;
import io.waveio.http.Headers;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpResponse;
import io.waveio.http.RequestUri;
import io.waveio.http.ResponseTransaction;
import io.waveio.registry.Registry;
import io.waveio.task.Task;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Temporary package-private plaintext server used while the public facade is built in M6. */
@SuppressWarnings("deprecation")
public final class PlaintextServer implements AutoCloseable {
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private final ExecutionRuntime runtime;
    private final Channel channel;
    private final ChannelGroup childChannels;
    private final ShutdownState shutdownState;
    private final AtomicBoolean stopped;

    private PlaintextServer(InetSocketAddress address, Handler handler, Registry registry, ExecutionConfig config, TransportConfig transportConfig, SslContext sslContext) {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        runtime = ExecutionRuntime.create(config);
        childChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        shutdownState = new ShutdownState();
        stopped = new AtomicBoolean();
        try {
            channel = new ServerBootstrap().group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel socket) {
                            childChannels.add(socket);
                            if (sslContext != null) { socket.pipeline().addLast(sslContext.newHandler(socket.alloc())); }
                            socket.pipeline().addLast(new ReadTimeoutHandler(transportConfig.idleTimeout().toNanos(), TimeUnit.NANOSECONDS));
                            socket.pipeline().addLast(new HttpServerCodec(transportConfig.maximumInitialLineLength(), transportConfig.maximumHeaderSize(), transportConfig.maximumChunkSize()));
                            socket.pipeline().addLast(new RequestHandler(handler, registry, runtime, shutdownState));
                        }
                    }).bind(address).syncUninterruptibly().channel();
        } catch (RuntimeException failure) {
            runtime.close();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup.shutdownGracefully().syncUninterruptibly();
            throw failure;
        }
    }

    public static PlaintextServer start(Handler handler, Registry registry, ExecutionConfig config, TransportConfig transportConfig) {
        return start(new InetSocketAddress(0), handler, registry, config, transportConfig);
    }

    /** Starts a plaintext server at the requested local address. */
    public static PlaintextServer start(InetSocketAddress address, Handler handler, Registry registry, ExecutionConfig config, TransportConfig transportConfig) {
        return new PlaintextServer(Objects.requireNonNull(address, "address"), Objects.requireNonNull(handler, "handler"), Objects.requireNonNull(registry, "registry"), Objects.requireNonNull(config, "config"), Objects.requireNonNull(transportConfig, "transportConfig"), null);
    }

    public static PlaintextServer startTls(Handler handler, Registry registry, ExecutionConfig config, TransportConfig transportConfig, SslContext sslContext) {
        return new PlaintextServer(new InetSocketAddress(0), Objects.requireNonNull(handler, "handler"), Objects.requireNonNull(registry, "registry"), Objects.requireNonNull(config, "config"), Objects.requireNonNull(transportConfig, "transportConfig"), Objects.requireNonNull(sslContext, "sslContext"));
    }

    /** Returns the bound local TCP port. */
    public int port() { return ((InetSocketAddress) channel.localAddress()).getPort(); }

    /** Returns the actual bound local TCP address. */
    public InetSocketAddress address() { return (InetSocketAddress) channel.localAddress(); }

    /** Stops accepting traffic, waits for active requests, then closes transport resources. */
    public void stop(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) { throw new IllegalArgumentException("timeout must not be negative"); }
        if (!stopped.compareAndSet(false, true)) { return; }
        shutdownState.beginStop();
        channel.close().syncUninterruptibly();
        shutdownState.awaitQuiescence(timeout);
        childChannels.close().syncUninterruptibly();
        runtime.close();
        workerGroup.shutdownGracefully().syncUninterruptibly();
        bossGroup.shutdownGracefully().syncUninterruptibly();
    }

    @Override public void close() { stop(Duration.ZERO); }

    private static final class RequestHandler extends ChannelInboundHandlerAdapter {
        private final Handler handler;
        private final Registry registry;
        private final ExecutionRuntime runtime;
        private final ShutdownState shutdownState;

        RequestHandler(Handler handler, Registry registry, ExecutionRuntime runtime, ShutdownState shutdownState) {
            this.handler = handler;
            this.registry = registry;
            this.runtime = runtime;
            this.shutdownState = shutdownState;
        }

        @Override public void channelRead(ChannelHandlerContext channel, Object message) {
            if (message instanceof HttpRequest request) {
                beginRequest(channel, request);
            } else if (message instanceof HttpContent content) {
                InboundBodyPublisher body = channel.channel().attr(InboundBodyPublisher.KEY).get();
                if (body == null) {
                    ReferenceCountUtil.release(content);
                    channel.close();
                } else {
                    body.onContent(content);
                }
            } else {
                ReferenceCountUtil.release(message);
            }
        }

        private void beginRequest(ChannelHandlerContext channel, HttpRequest request) {
            if (shutdownState.stopping()) {
                channel.close();
                return;
            }
            if (!request.decoderResult().isSuccess()) {
                channel.close();
                return;
            }
            channel.channel().config().setAutoRead(false);
            shutdownState.started();
            InboundBodyPublisher body = new InboundBodyPublisher(channel, HttpUtil.is100ContinueExpected(request));
            channel.channel().attr(InboundBodyPublisher.KEY).set(body);
            ResponseTransaction response = new ResponseTransaction();
            Context context = new RequestContext(toWaveRequest(request, io.waveio.http.Body.of(body)), registry, response);
            try {
                handler.handle(context).run(runtime).whenComplete((ignored, failure) -> {
                    if (failure != null || response.committed().isEmpty()) {
                        write(channel, HttpResponse.of(io.waveio.http.HttpStatus.INTERNAL_SERVER_ERROR), () -> finish(channel, false, shutdownState));
                    } else {
                        write(channel, response.committed().orElseThrow(), () -> finish(channel, HttpUtil.isKeepAlive(request), shutdownState));
                    }
                });
            } catch (Throwable failure) {
                write(channel, HttpResponse.of(io.waveio.http.HttpStatus.INTERNAL_SERVER_ERROR), () -> finish(channel, false, shutdownState));
            }
        }

        private static void finish(ChannelHandlerContext channel, boolean keepAlive, ShutdownState shutdownState) {
            shutdownState.completed();
            if (keepAlive && !shutdownState.stopping() && channel.channel().isActive()) {
                channel.channel().config().setAutoRead(true);
                channel.read();
            } else {
                channel.close();
            }
        }

        private static io.waveio.http.HttpRequest toWaveRequest(HttpRequest request, io.waveio.http.Body body) {
            return new io.waveio.http.HttpRequest(HttpMethod.valueOf(request.method().name()), RequestUri.parse(request.uri()), Headers.empty(), body);
        }

        private static void write(ChannelHandlerContext channel, HttpResponse response, Runnable complete) {
            if (!channel.channel().isActive()) { return; }
            if (response.body().isKnownEmpty()) {
                writeEmpty(channel, response, complete);
                return;
            }
            DefaultHttpResponse outbound = new DefaultHttpResponse(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                    new HttpResponseStatus(response.status().code(), response.status().reason()));
            response.headers().forEach((name, value) -> outbound.headers().add(name, value));
            HttpUtil.setTransferEncodingChunked(outbound, true);
            channel.writeAndFlush(outbound).addListener(future -> {
                if (future.isSuccess()) {
                    response.body().subscribe(new OutboundBodyWriter(channel, complete));
                } else {
                    channel.close();
                }
            });
        }

        private static void writeEmpty(ChannelHandlerContext channel, HttpResponse response, Runnable complete) {
            DefaultFullHttpResponse outbound = new DefaultFullHttpResponse(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                    new HttpResponseStatus(response.status().code(), response.status().reason()), Unpooled.EMPTY_BUFFER);
            response.headers().forEach((name, value) -> outbound.headers().add(name, value));
            if (!outbound.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                outbound.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            }
            channel.writeAndFlush(outbound).addListener(future -> {
                if (future.isSuccess()) { complete.run(); } else { channel.close(); }
            });
        }
    }

    private static final class ShutdownState {
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final AtomicInteger activeRequests = new AtomicInteger();

        boolean stopping() { return stopping.get(); }
        void beginStop() { stopping.set(true); }
        void started() { activeRequests.incrementAndGet(); }
        synchronized void completed() { activeRequests.decrementAndGet(); notifyAll(); }
        synchronized void awaitQuiescence(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (activeRequests.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { return; }
                try { TimeUnit.NANOSECONDS.timedWait(this, remaining); } catch (InterruptedException interruption) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private static final class RequestContext implements Context {
        private final io.waveio.http.HttpRequest request;
        private final Registry registry;
        private final ResponseTransaction response;

        RequestContext(io.waveio.http.HttpRequest request, Registry registry, ResponseTransaction response) {
            this.request = request;
            this.registry = registry;
            this.response = response;
        }

        @Override public io.waveio.http.HttpRequest request() { return request; }
        @Override public io.waveio.http.Body body() { return request.body(); }
        @Override public Registry registry() { return registry; }
        @Override public Map<String, String> pathParameters() { return Map.of(); }
        @Override public void respond(HttpResponse candidate) { response.commit(candidate); }
        @Override public Task<Void> next() { return Task.failure(new IllegalStateException("no handler chain is installed")); }
        @Override public Task<Void> insert(List<Handler> handlers) { return Task.failure(new IllegalStateException("no handler chain is installed")); }
    }

    private static final class OutboundBodyWriter implements Flow.Subscriber<ByteBuffer> {
        private final ChannelHandlerContext channel;
        private final Runnable complete;
        private Flow.Subscription subscription;

        OutboundBodyWriter(ChannelHandlerContext channel, Runnable complete) { this.channel = channel; this.complete = complete; }

        @Override public void onSubscribe(Flow.Subscription candidate) {
            if (subscription != null) {
                candidate.cancel();
                return;
            }
            subscription = candidate;
            candidate.request(1);
        }

        @Override public void onNext(ByteBuffer bytes) {
            Objects.requireNonNull(bytes, "bytes");
            channel.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(bytes))).addListener(future -> {
                if (future.isSuccess()) {
                    subscription.request(1);
                } else {
                    subscription.cancel();
                    channel.close();
                }
            });
        }

        @Override public void onError(Throwable failure) { channel.close(); }

        @Override public void onComplete() {
            channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
                if (future.isSuccess()) { complete.run(); } else { channel.close(); }
            });
        }
    }

    private static final class InboundBodyPublisher implements Flow.Publisher<ByteBuffer> {
        static final io.netty.util.AttributeKey<InboundBodyPublisher> KEY = io.netty.util.AttributeKey.valueOf("waveio.inbound-body");
        private final ChannelHandlerContext channel;
        private final boolean sendContinue;
        private Flow.Subscriber<? super ByteBuffer> subscriber;
        private long demand;
        private boolean subscribed;
        private boolean completed;
        private boolean readInFlight;
        private boolean continueSent;

        InboundBodyPublisher(ChannelHandlerContext channel, boolean sendContinue) { this.channel = channel; this.sendContinue = sendContinue; }

        @Override public synchronized void subscribe(Flow.Subscriber<? super ByteBuffer> candidate) {
            Objects.requireNonNull(candidate, "subscriber");
            if (subscribed) {
                candidate.onSubscribe(new EmptySubscription());
                candidate.onError(new IllegalStateException("HTTP body has already been consumed"));
                return;
            }
            subscribed = true;
            subscriber = candidate;
            candidate.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { requestMore(count); }
                @Override public void cancel() { InboundBodyPublisher.this.cancel(); }
            });
        }

        synchronized void onContent(HttpContent content) {
            try {
                readInFlight = false;
                if (completed) {
                    return;
                }
                if (subscriber == null && content instanceof LastHttpContent && content.content().readableBytes() == 0) {
                    completed = true;
                    return;
                }
                if (subscriber == null) {
                    completed = true;
                    channel.close();
                    return;
                }
                boolean last = content instanceof LastHttpContent;
                int size = content.content().readableBytes();
                if (size > 0 && demand == 0) {
                    fail(new IllegalStateException("received HTTP body bytes without demand"));
                    return;
                }
                if (size > 0) {
                    byte[] bytes = new byte[size];
                    content.content().getBytes(content.content().readerIndex(), bytes);
                    demand--;
                    subscriber.onNext(ByteBuffer.wrap(bytes).asReadOnlyBuffer());
                }
                if (last) {
                    completed = true;
                    subscriber.onComplete();
                } else {
                    scheduleRead();
                }
            } finally {
                ReferenceCountUtil.release(content);
            }
        }

        private synchronized void requestMore(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("non-positive demand"));
                return;
            }
            if (completed) { return; }
            demand = Math.min(Long.MAX_VALUE, demand + count);
            scheduleRead();
        }

        private synchronized void cancel() {
            if (!completed) {
                completed = true;
                channel.close();
            }
        }

        private void scheduleRead() {
            if (!completed && demand > 0 && !readInFlight) {
                readInFlight = true;
                if (sendContinue && !continueSent) {
                    continueSent = true;
                    channel.writeAndFlush(new DefaultFullHttpResponse(io.netty.handler.codec.http.HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE))
                            .addListener(future -> { if (future.isSuccess()) { channel.read(); } else { channel.close(); } });
                } else {
                    channel.executor().execute(channel::read);
                }
            }
        }

        private void fail(Throwable failure) {
            if (!completed) {
                completed = true;
                subscriber.onError(failure);
                channel.close();
            }
        }

        private static final class EmptySubscription implements Flow.Subscription {
            @Override public void request(long count) { }
            @Override public void cancel() { }
        }
    }
}
