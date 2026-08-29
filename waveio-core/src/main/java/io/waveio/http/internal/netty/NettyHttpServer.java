package io.waveio.http.internal.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.waveio.http.internal.ServerConfig;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.Router;
import io.waveio.http.server.ExceptionHandler;
import io.waveio.http.server.RequestObserver;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class NettyHttpServer {
    private enum State { NEW, RUNNING, STOPPED }

    private final ServerConfig config;
    private final Router router;
    private final List<Middleware> middleware;
    private final ExceptionHandler exceptionHandler;
    private final List<RequestObserver> observers;
    private NioEventLoopGroup acceptGroup;
    private NioEventLoopGroup workerGroup;
    private java.util.concurrent.ExecutorService blockingExecutor;
    private ChannelGroup connections;
    private volatile State state = State.NEW;
    private volatile Channel serverChannel;
    private final CountDownLatch terminated = new CountDownLatch(1);

    public NettyHttpServer(ServerConfig config, Router router, List<Middleware> middleware,
            ExceptionHandler exceptionHandler, List<RequestObserver> observers) {
        this.config = config;
        this.router = router;
        this.middleware = middleware;
        this.exceptionHandler = exceptionHandler;
        this.observers = List.copyOf(observers);
    }

    public void start() {
        if (state != State.NEW) {
            throw new IllegalStateException("Server can only be started once");
        }
        try {
            acceptGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
            connections = new DefaultChannelGroup(workerGroup.next());
            blockingExecutor = Executors.newVirtualThreadPerTaskExecutor();
            var admission = new ConnectionAdmissionHandler(config.maxConnections());
            var sslContext = sslContext();
            var bootstrap = new ServerBootstrap()
                    .group(acceptGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            connections.add(channel);
                            var idle = new ConnectionIdleHandler(config.idleTimeout(),
                                    config.readTimeout());
                            var requestHandler = new NettyRequestHandler(router, middleware,
                                    exceptionHandler, blockingExecutor,
                                    new ConnectionOptions(
                                            config.maxPendingRequestsPerConnection(),
                                            config.handlerTimeout(), config.writeTimeout(),
                                            observers, config.bodyCodec()));
                            var pipeline = channel.pipeline().addLast(admission);
                            if (sslContext != null) {
                                pipeline.addLast(sslContext.newHandler(channel.alloc()));
                            }
                            pipeline.addLast(idle)
                                    .addLast(new WriteTimeoutHandler(
                                            timeoutNanos(config.writeTimeout()), TimeUnit.NANOSECONDS))
                                    .addLast(new HttpServerCodec(
                                            config.maxInitialLineLength(),
                                            config.maxHeaderSize(),
                                            8_192))
                                    .addLast(new NettyInboundRequestHandler(router,
                                            config.maxBodySize(), config.bodyCodec()))
                                    .addLast(requestHandler);
                        }
                    });
            serverChannel = bootstrap.bind(config.host(), config.port()).sync().channel();
            state = State.RUNNING;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            state = State.STOPPED;
            stopResources();
            terminated.countDown();
            throw new IllegalStateException("Interrupted while starting HTTP server", failure);
        } catch (RuntimeException failure) {
            state = State.STOPPED;
            stopResources();
            terminated.countDown();
            throw failure;
        }
    }

    public boolean isRunning() {
        var channel = serverChannel;
        return state == State.RUNNING && channel != null && channel.isActive();
    }

    public int localPort() {
        var channel = serverChannel;
        if (!isRunning() || !(channel.localAddress() instanceof InetSocketAddress address)) {
            throw new IllegalStateException("Server is not running");
        }
        return address.getPort();
    }

    public void stop() {
        if (state == State.STOPPED) return;
        state = State.STOPPED;
        try {
            var channel = serverChannel;
            if (channel != null) channel.close().syncUninterruptibly();
            if (connections != null) connections.close().awaitUninterruptibly(
                    config.shutdownTimeout().toMillis());
            stopResources();
        } finally {
            terminated.countDown();
        }
    }

    public void awaitTermination() throws InterruptedException {
        ensureStartedOrStopped();
        terminated.await();
    }

    public void awaitTerminationUninterruptibly() {
        ensureStartedOrStopped();
        boolean interrupted = false;
        while (true) {
            try {
                terminated.await();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void ensureStartedOrStopped() {
        if (state == State.NEW) {
            throw new IllegalStateException("Server has not been started");
        }
    }

    private void stopResources() {
        long timeoutMillis = config.shutdownTimeout().toMillis();
        if (blockingExecutor != null) {
            blockingExecutor.shutdown();
            try {
                if (!blockingExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    blockingExecutor.shutdownNow();
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                blockingExecutor.shutdownNow();
            }
        }
        if (acceptGroup != null) {
            acceptGroup.shutdownGracefully(0, timeoutMillis, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, timeoutMillis, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    private static long timeoutNanos(java.time.Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException failure) {
            return Long.MAX_VALUE;
        }
    }

    private SslContext sslContext() {
        var tls = config.tls();
        if (tls == null) return null;
        try {
            return SslContextBuilder.forServer(tls.certificateChain().toFile(),
                    tls.privateKey().toFile()).build();
        } catch (javax.net.ssl.SSLException failure) {
            throw new IllegalArgumentException("Invalid TLS certificate chain or private key",
                    failure);
        }
    }
}
