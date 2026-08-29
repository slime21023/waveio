package io.waveio.http.internal.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.waveio.http.HttpHeaders;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpRequest;
import io.waveio.http.body.RequestBody;
import io.waveio.http.internal.RequestTargetParser;
import java.net.InetSocketAddress;
import java.util.Map;

final class NettyRequestAdapter {
    private final io.waveio.http.body.BodyCodec codec;

    NettyRequestAdapter() {
        this(null);
    }

    NettyRequestAdapter(io.waveio.http.body.BodyCodec codec) {
        this.codec = codec;
    }

    HttpRequest adapt(ChannelHandlerContext context, FullHttpRequest source) {
        return adapt(context, (io.netty.handler.codec.http.HttpRequest) source)
                .withBody(body(source));
    }

    HttpRequest adapt(ChannelHandlerContext context,
            io.netty.handler.codec.http.HttpRequest source) {
        if (!source.decoderResult().isSuccess()) {
            throw new IllegalArgumentException("Invalid HTTP request", source.decoderResult().cause());
        }
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(source.method().name());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unsupported HTTP method", failure);
        }
        var target = RequestTargetParser.parse(source.uri());
        var headers = HttpHeaders.builder();
        source.headers().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));
        InetSocketAddress remote = context.channel().remoteAddress() instanceof InetSocketAddress address
                ? address : null;
        return new HttpRequest(method, target.path(), headers.build(), RequestBody.empty(),
                remote, target.queryParameters(), Map.of()).withCodec(codec);
    }

    HttpRequest rejected(ChannelHandlerContext context,
            io.netty.handler.codec.http.HttpRequest source) {
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(source.method().name());
        } catch (IllegalArgumentException failure) {
            method = HttpMethod.GET;
        }
        var headers = HttpHeaders.builder();
        source.headers().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));
        InetSocketAddress remote = context.channel().remoteAddress() instanceof InetSocketAddress address
                ? address : null;
        return new HttpRequest(method, "/", headers.build(), RequestBody.empty(), remote,
                Map.of(), Map.of()).withCodec(codec);
    }

    private static RequestBody body(FullHttpRequest source) {
        byte[] body = new byte[source.content().readableBytes()];
        source.content().getBytes(source.content().readerIndex(), body);
        return RequestBody.of(body);
    }
}
