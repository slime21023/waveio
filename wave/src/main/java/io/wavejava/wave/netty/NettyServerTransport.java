package io.wavejava.wave.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.wavejava.wave.api.server.RunningServer;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.api.server.TlsConfig;
import io.wavejava.wave.api.server.RequestBodyMode;
import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import io.wavejava.wave.api.http.Http2Config;
import io.wavejava.wave.runtime.InvocationRuntime;
import io.wavejava.wave.runtime.ObservabilityDispatcher;
import io.wavejava.wave.runtime.RequestDispatcher;
import io.wavejava.wave.runtime.server.ServerTransport;
import io.wavejava.wave.runtime.server.ServerRuntime;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Internal Netty HTTP/1.1 listener. Netty types do not cross this package boundary. */
public final class NettyServerTransport implements ServerTransport {
    public NettyServerTransport() {
    }

    /** Starts a server with the selected bounded aggregate or Flow request-body transport. */
    @Override
    public RunningServer start(
            ServerRuntime runtime,
            int port,
            ServerLimits limits,
            ServerTimeouts timeouts,
            TlsConfig tls,
            ForwardedHeaderPolicy forwardedHeaders,
            Http2Config http2,
            boolean compression,
            RequestBodyMode requestBodyMode) {
        Objects.requireNonNull(runtime, "runtime");
        var app = runtime.dispatcher();
        var invocations = runtime.invocations();
        var observationDispatcher = runtime.observability();
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(requestBodyMode, "requestBodyMode");
        Objects.requireNonNull(forwardedHeaders, "forwardedHeaders");
        Objects.requireNonNull(http2, "http2");
        var boss = new NioEventLoopGroup(1);
        var workers = new NioEventLoopGroup();
        var connections = new AtomicInteger();
        var connectionLifecycle = new ConnectionLifecycleManager();
        var inFlightRequests = new Semaphore(limits.maximumInFlightRequests(), true);
        try {
            var sslContext = tls == null ? null : TlsSupport.build(tls, http2);
            Channel channel = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // A WebSocket peer half-close cannot be a usable session state. Closing the
                    // channel on input EOF makes disconnect cancellation deterministic instead
                    // of leaving a write-only session alive until an unrelated timeout fires.
                    .childOption(ChannelOption.ALLOW_HALF_CLOSURE, false)
                    .childHandler(new ChildInitializer(
                            app,
                            invocations,
                            limits,
                            timeouts,
                            sslContext,
                            forwardedHeaders,
                            http2,
                            compression,
                            requestBodyMode,
                            connections,
                            connectionLifecycle,
                            inFlightRequests,
                            observationDispatcher))
                    .bind(port)
                    .syncUninterruptibly()
                    .channel();
            return new NettyServerHandle(
                    channel,
                    boss,
                    workers,
                    connectionLifecycle,
                    timeouts,
                    runtime);
        } catch (RuntimeException failure) {
            connectionLifecycle.stopAccepting();
            connectionLifecycle.closeActiveConnections();
            shutdown(boss);
            shutdown(workers);
            throw failure;
        }
    }

    private static void shutdown(EventLoopGroup group) {
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }

    private static final class ChildInitializer extends ChannelInitializer<SocketChannel> {
        private final RequestDispatcher app;
        private final InvocationRuntime invocations;
        private final ServerLimits limits;
        private final ServerTimeouts timeouts;
        private final io.netty.handler.ssl.SslContext sslContext;
        private final ForwardedHeaderPolicy forwardedHeaders;
        private final Http2Config http2;
        private final boolean compression;
        private final RequestBodyMode requestBodyMode;
        private final AtomicInteger connections;
        private final ConnectionLifecycleManager connectionLifecycle;
        private final Semaphore inFlightRequests;
        private final ObservabilityDispatcher observationDispatcher;

        private ChildInitializer(
                RequestDispatcher app,
                InvocationRuntime invocations,
                ServerLimits limits,
                ServerTimeouts timeouts,
                io.netty.handler.ssl.SslContext sslContext,
                ForwardedHeaderPolicy forwardedHeaders,
                Http2Config http2,
                boolean compression,
                RequestBodyMode requestBodyMode,
                AtomicInteger connections,
                ConnectionLifecycleManager connectionLifecycle,
                Semaphore inFlightRequests,
                ObservabilityDispatcher observationDispatcher) {
            this.app = app;
            this.invocations = invocations;
            this.limits = limits;
            this.timeouts = timeouts;
            this.sslContext = sslContext;
            this.forwardedHeaders = forwardedHeaders;
            this.http2 = http2;
            this.compression = compression;
            this.requestBodyMode = requestBodyMode;
            this.connections = connections;
            this.connectionLifecycle = connectionLifecycle;
            this.inFlightRequests = inFlightRequests;
            this.observationDispatcher = observationDispatcher;
        }

        @Override
        protected void initChannel(SocketChannel channel) {
            if (!connectionLifecycle.register(channel)) {
                channel.close();
                return;
            }
            if (sslContext != null) {
                channel.pipeline().addLast(sslContext.newHandler(channel.alloc()));
            }
            channel.pipeline().addLast(new ConnectionLimitHandler(connections, limits.maximumConnections()));
            channel.pipeline().addLast(new ReadTimeoutHandler(timeouts.readTimeout().toMillis(), TimeUnit.MILLISECONDS));
            channel.pipeline().addLast(new WriteTimeoutHandler(timeouts.writeTimeout().toMillis(), TimeUnit.MILLISECONDS));
            channel.pipeline().addLast(new IdleStateHandler(0, 0, timeouts.idleTimeout().toMillis(), TimeUnit.MILLISECONDS));
            if (http2.isEnabled()) {
                configureAlpn(channel.pipeline());
                return;
            }
            configureHttp1(channel.pipeline());
        }

        private void configureAlpn(ChannelPipeline pipeline) {
            if (sslContext == null) {
                throw new IllegalStateException("HTTP/2 requires a TLS pipeline");
            }
            var fallback = http2.requiresHttp2() ? "wave-no-alpn" : ApplicationProtocolNames.HTTP_1_1;
            pipeline.addLast(new ApplicationProtocolNegotiationHandler(fallback) {
                @Override
                protected void configurePipeline(ChannelHandlerContext context, String protocol) {
                    if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                        configureHttp2(context.pipeline());
                        return;
                    }
                    if (ApplicationProtocolNames.HTTP_1_1.equals(protocol) && !http2.requiresHttp2()) {
                        configureHttp1(context.pipeline());
                        return;
                    }
                    throw new IllegalStateException("TLS peer did not negotiate required HTTP/2 ALPN");
                }
            });
        }

        private void configureHttp1(ChannelPipeline pipeline) {
            if (requestBodyMode == RequestBodyMode.STREAMING) {
                var codec = new DemandDrivenHttp1Codec(
                        limits.maximumRequestLineBytes(),
                        limits.maximumRequestHeaderBytes(),
                        limits.maximumRequestBodyBytes());
                pipeline.addLast(codec.requestDecoder());
                pipeline.addLast(codec.responseEncoder());
            } else {
                pipeline.addLast(new HttpServerCodec(
                        limits.maximumRequestLineBytes(),
                        limits.maximumRequestHeaderBytes(), limits.maximumRequestBodyBytes()));
            }
            if (compression) {
                pipeline.addLast(CompressionAdapter.create());
            }
            if (requestBodyMode == RequestBodyMode.STREAMING) {
                pipeline.addLast(new LimitAwareExpectContinueHandler(limits.maximumRequestBodyBytes()));
            } else {
                pipeline.addLast(new HttpServerExpectContinueHandler());
            }
            if (requestBodyMode == RequestBodyMode.AGGREGATED) {
                pipeline.addLast(new HttpObjectAggregator(limits.maximumRequestBodyBytes()));
            }
            pipeline.addLast(new RequestDispatchHandler(
                    app, invocations, limits, timeouts, inFlightRequests, requestBodyMode, observationDispatcher, forwardedHeaders));
        }

        private void configureHttp2(ChannelPipeline pipeline) {
            var connectionState = new Http2ConnectionState(http2, limits);
            var settings = Http2Settings.defaultSettings()
                    .maxConcurrentStreams((long) http2.maximumConcurrentStreams())
                    .maxHeaderListSize((long) http2.maximumHeaderListBytes())
                    .maxFrameSize(http2.maximumFrameBytes())
                    .initialWindowSize(http2.initialStreamWindowBytes());
            var frameCodec = Http2FrameCodecBuilder.forServer()
                    .initialSettings(settings)
                    .validateHeaders(true)
                    .validateRequiredPseudoHeaders(true)
                    .decoderEnforceMaxConsecutiveEmptyDataFrames(8)
                    .decoderEnforceMaxRstFramesPerWindow(16, 30)
                    .build();
            pipeline.addLast(frameCodec);
            pipeline.addLast(new InitialConnectionWindowHandler(frameCodec, http2.initialConnectionWindowBytes()));
            pipeline.addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel stream) {
                    var streamPipeline = stream.pipeline();
                    streamPipeline.addLast(new Http2StreamFrameToHttpObjectCodec(true, true));
                    if (requestBodyMode == RequestBodyMode.AGGREGATED) {
                        streamPipeline.addLast(new HttpObjectAggregator(connectionState.maximumAggregateRequestBodyBytes()));
                    }
                    streamPipeline.addLast(new Http2StreamDispatchHandler(
                            app,
                            invocations,
                            limits,
                            timeouts,
                            inFlightRequests,
                            connectionState,
                            observationDispatcher,
                            forwardedHeaders,
                            requestBodyMode));
                }
            }));
            pipeline.addLast(new Http2ProtocolExceptionSink());
            connectionLifecycle.registerHttp2(pipeline.channel(), connectionState);
        }

        /**
         * Consumes fatal HTTP/2 codec exceptions after {@link Http2FrameCodec} has emitted its
         * protocol GOAWAY and scheduled the matching close. Closing here would race that sequence
         * and can replace the negotiated error code with an accidental {@code NO_ERROR} GOAWAY.
         */
        private static final class Http2ProtocolExceptionSink extends ChannelInboundHandlerAdapter {
            @Override
            public void exceptionCaught(ChannelHandlerContext context, Throwable failure) {
                if (Http2CodecUtil.getEmbeddedHttp2Exception(failure) == null) {
                    context.fireExceptionCaught(failure);
                }
            }
        }

        /** Raises Netty's default connection receive window when the explicit limit requests it. */
        private static final class InitialConnectionWindowHandler extends ChannelInboundHandlerAdapter {
            private final Http2FrameCodec codec;
            private final int configuredWindow;

            private InitialConnectionWindowHandler(Http2FrameCodec codec, int configuredWindow) {
                this.codec = codec;
                this.configuredWindow = configuredWindow;
            }

            @Override
            public void handlerAdded(ChannelHandlerContext context) {
                var controller = codec.decoder().flowController();
                var connectionStream = codec.connection().connectionStream();
                var delta = configuredWindow - controller.windowSize(connectionStream);
                if (delta > 0) {
                    try {
                        controller.incrementWindowSize(connectionStream, delta);
                    } catch (io.netty.handler.codec.http2.Http2Exception failure) {
                        context.close();
                    }
                }
            }
        }
    }
}

