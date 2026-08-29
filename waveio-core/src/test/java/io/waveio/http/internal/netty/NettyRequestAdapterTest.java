package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class NettyRequestAdapterTest {

    @Test
    void adaptsValidFullHttpRequest() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);

        var nettyReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/api/test?query1=val1&query1=val2&query2=abc",
                Unpooled.copiedBuffer("body data", StandardCharsets.UTF_8));
        nettyReq.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/plain");

        var adapter = new NettyRequestAdapter();
        var request = adapter.adapt(context, nettyReq);

        assertEquals(io.waveio.http.HttpMethod.POST, request.method());
        assertEquals("/api/test", request.path());
        assertEquals("body data", request.body().text());
        assertEquals(List.of("val1", "val2"), request.queryParams("query1"));
        assertEquals("abc", request.queryParam("query2").orElseThrow());
        assertEquals("text/plain", request.headers().first("content-type").orElseThrow());
        assertNull(request.remoteAddress()); // EmbeddedChannel has null remoteAddress
    }

    @Test
    void rejectsFailedDecoderResult() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);

        var nettyReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/invalid");
        nettyReq.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("bad uri")));

        var adapter = new NettyRequestAdapter();
        var ex = assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, nettyReq));
        assertEquals("Invalid HTTP request", ex.getMessage());
    }

    @Test
    void rejectsUnsupportedHttpMethod() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);

        var nettyReq = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.valueOf("PROPFIND"),
                "/webdav");

        var adapter = new NettyRequestAdapter();
        var ex = assertThrows(IllegalArgumentException.class, () -> adapter.adapt(context, nettyReq));
        assertEquals("Unsupported HTTP method", ex.getMessage());
    }

    @Test
    void decodesPathSegmentsAndQueryComponentsWithDistinctPlusRules() {
        var request = adapt("/caf%C3%A9/a+b?tag=a+b&tag=c%2Bd&na%6De=v%20x");

        assertEquals("/café/a+b", request.path());
        assertEquals(List.of("a b", "c+d"), request.queryParams("tag"));
        assertEquals("v x", request.queryParam("name").orElseThrow());
    }

    @Test
    void acceptsAbsoluteFormAndExtractsItsPathAndQuery() {
        var request = adapt("http://example.test/api/items%20one?q=hello+world");

        assertEquals("/api/items one", request.path());
        assertEquals("hello world", request.queryParam("q").orElseThrow());
    }

    @Test
    void rejectsEncodedSeparatorsDotSegmentsAndFragments() {
        for (String target : List.of(
                "/files/a%2Fb",
                "/files/a%5Cb",
                "/safe/%2e%2e/secret",
                "/safe/./file",
                "/path#fragment")) {
            assertThrows(IllegalArgumentException.class, () -> adapt(target), target);
        }
    }

    @Test
    void rejectsMalformedEscapesAndInvalidUtf8() {
        for (String target : List.of("/bad/%", "/bad/%2", "/bad/%GG", "/bad/%C3%28")) {
            assertThrows(IllegalArgumentException.class, () -> adapt(target), target);
        }
    }

    @Test
    void rejectsUnsupportedRequestTargetForms() {
        for (String target : List.of("relative/path", "ftp://example.test/file", "//example.test/path")) {
            assertThrows(IllegalArgumentException.class, () -> adapt(target), target);
        }
    }

    private static io.waveio.http.HttpRequest adapt(String target) {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var nettyRequest = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, target);
        return new NettyRequestAdapter().adapt(context, nettyRequest);
    }
}
