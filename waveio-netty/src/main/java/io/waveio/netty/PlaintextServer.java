package io.waveio.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.buffer.Unpooled;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Temporary package-private plaintext server used while the public facade is built in M6. */
@SuppressWarnings("deprecation")
final class PlaintextServer implements AutoCloseable {
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private final ExecutionRuntime runtime;
    private final Channel channel;

    private PlaintextServer(Handler handler, Registry registry, ExecutionConfig config) {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        runtime = ExecutionRuntime.create(config);
        try {
            channel = new ServerBootstrap().group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel socket) {
                            socket.pipeline().addLast(new HttpServerCodec());
                            socket.pipeline().addLast(new RequestHandler(handler, registry, runtime));
                        }
                    }).bind(0).syncUninterruptibly().channel();
        } catch (RuntimeException failure) {
            runtime.close();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup.shutdownGracefully().syncUninterruptibly();
            throw failure;
        }
    }

    static PlaintextServer start(Handler handler, Registry registry, ExecutionConfig config) {
        return new PlaintextServer(Objects.requireNonNull(handler, "handler"), Objects.requireNonNull(registry, "registry"), Objects.requireNonNull(config, "config"));
    }

    int port() { return ((InetSocketAddress) channel.localAddress()).getPort(); }

    @Override public void close() {
        channel.close().syncUninterruptibly();
        runtime.close();
        workerGroup.shutdownGracefully().syncUninterruptibly();
        bossGroup.shutdownGracefully().syncUninterruptibly();
    }

    private static final class RequestHandler extends SimpleChannelInboundHandler<HttpRequest> {
        private final Handler handler;
        private final Registry registry;
        private final ExecutionRuntime runtime;

        RequestHandler(Handler handler, Registry registry, ExecutionRuntime runtime) {
            this.handler = handler;
            this.registry = registry;
            this.runtime = runtime;
        }

        @Override protected void channelRead0(ChannelHandlerContext channel, HttpRequest request) {
            ResponseTransaction response = new ResponseTransaction();
            Context context = new RequestContext(toWaveRequest(request), registry, response);
            try {
                handler.handle(context).run(runtime).whenComplete((ignored, failure) -> {
                    if (failure != null || response.committed().isEmpty()) {
                        write(channel, HttpResponse.of(io.waveio.http.HttpStatus.INTERNAL_SERVER_ERROR));
                    } else {
                        write(channel, response.committed().orElseThrow());
                    }
                });
            } catch (Throwable failure) {
                write(channel, HttpResponse.of(io.waveio.http.HttpStatus.INTERNAL_SERVER_ERROR));
            }
        }

        private static io.waveio.http.HttpRequest toWaveRequest(HttpRequest request) {
            return new io.waveio.http.HttpRequest(HttpMethod.valueOf(request.method().name()), RequestUri.parse(request.uri()), Headers.empty());
        }

        private static void write(ChannelHandlerContext channel, HttpResponse response) {
            DefaultFullHttpResponse outbound = new DefaultFullHttpResponse(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                    new HttpResponseStatus(response.status().code(), response.status().reason()), Unpooled.EMPTY_BUFFER);
            outbound.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            channel.writeAndFlush(outbound);
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
        @Override public Registry registry() { return registry; }
        @Override public Map<String, String> pathParameters() { return Map.of(); }
        @Override public void respond(HttpResponse candidate) { response.commit(candidate); }
        @Override public Task<Void> next() { return Task.failure(new IllegalStateException("no handler chain is installed")); }
        @Override public Task<Void> insert(List<Handler> handlers) { return Task.failure(new IllegalStateException("no handler chain is installed")); }
    }
}
