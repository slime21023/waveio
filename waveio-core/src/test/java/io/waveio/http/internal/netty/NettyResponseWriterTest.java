package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.body.ResponseBody;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NettyResponseWriterTest {

    @Test
    void reportsFailedSocketWriteInsteadOfSuccessfulCompletion() {
        var failure = new IllegalStateException("socket write failed");
        var failing = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message,
                    ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                promise.setFailure(failure);
            }
        };
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(failing, handler);
        var context = channel.pipeline().context(handler);
        var result = new AtomicReference<WriteResult>();

        new NettyResponseWriter().write(context, HttpMethod.GET,
                HttpResponse.text("response"), true,
                (WriteResult completed) -> result.set(completed));

        assertTrue(result.get() instanceof WriteResult.Failure);
        assertEquals(failure, ((WriteResult.Failure) result.get()).cause());
    }

    @Test
    void headWritesRepresentationLengthWithoutBodyBytes() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);

        new NettyResponseWriter().write(context, HttpMethod.HEAD,
                HttpResponse.text("representation"), true, ignored -> {});

        FullHttpResponse written = channel.readOutbound();
        assertNotNull(written);
        assertEquals("14", written.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, written.content().readableBytes());
        written.release();
    }

    @Test
    void bodyForbiddenStatusesSuppressBytesAndFramingHeaders() {
        for (var status : new HttpStatus[] {
                HttpStatus.of(103, "Early Hints"),
                HttpStatus.NO_CONTENT,
                HttpStatus.of(304, "Not Modified")}) {
            var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
            var channel = new EmbeddedChannel(handler);
            var context = channel.pipeline().context(handler);
            var response = HttpResponse.status(status)
                    .header("content-length", "999")
                    .header("transfer-encoding", "chunked")
                    .body("must not be sent");

            new NettyResponseWriter().write(context, HttpMethod.GET,
                    response, true, ignored -> {});

            FullHttpResponse written = channel.readOutbound();
            assertNotNull(written);
            assertEquals(0, written.content().readableBytes());
            assertFalse(written.headers().contains(HttpHeaderNames.CONTENT_LENGTH));
            assertFalse(written.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
            written.release();
        }
    }

    @Test
    void waveioOwnsFramingHeadersForByteResponses() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var response = HttpResponse.status(HttpStatus.OK)
                .header("content-length", "999")
                .header("transfer-encoding", "chunked")
                .body("actual");

        new NettyResponseWriter().write(context, HttpMethod.GET,
                response, true, ignored -> {});

        FullHttpResponse written = channel.readOutbound();
        assertEquals("6", written.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertFalse(written.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
        written.release();
    }

    @Test
    void headDoesNotSubscribeToStreamingBody() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var subscribed = new AtomicBoolean();
        Flow.Publisher<ByteBuffer> publisher = subscriber -> subscribed.set(true);

        new NettyResponseWriter().write(context, HttpMethod.HEAD,
                HttpResponse.status(HttpStatus.OK).body(ResponseBody.stream(publisher, 12)),
                true, ignored -> {});

        io.netty.handler.codec.http.HttpResponse written = channel.readOutbound();
        assertEquals("12", written.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertFalse(written.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
        assertFalse(subscribed.get());
        assertFalse(channel.outboundMessages().contains(LastHttpContent.EMPTY_LAST_CONTENT));
    }

    @Test
    void writesBytesResponseWithContentLength() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var writer = new NettyResponseWriter();
        var completed = new AtomicBoolean();

        var response = HttpResponse.status(HttpStatus.OK)
                .header("X-Custom", "abc")
                .body("test bytes".getBytes(StandardCharsets.UTF_8));

        writer.write(context, response, true, ignored -> completed.set(true));

        assertTrue(completed.get());
        FullHttpResponse written = channel.readOutbound();
        assertNotNull(written);
        assertEquals(HttpResponseStatus.OK, written.status());
        assertEquals("abc", written.headers().get("x-custom"));
        assertEquals("10", written.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        written.release();
    }

    @Test
    void writesStreamResponseWithChunkedEncoding() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var writer = new NettyResponseWriter();
        var completed = new AtomicBoolean();

        Flow.Publisher<ByteBuffer> publisher = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onComplete();
                }

                @Override public void cancel() {}
            });
        };

        var response = HttpResponse.status(HttpStatus.OK)
                .body(ResponseBody.stream(publisher));

        writer.write(context, response, false, ignored -> completed.set(true));

        io.netty.handler.codec.http.HttpResponse initial = channel.readOutbound();
        assertNotNull(initial);
        assertEquals("chunked", initial.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
    }

    @Test
    void writesStreamResponseWithContentLength() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var writer = new NettyResponseWriter();
        var completed = new AtomicBoolean();

        Flow.Publisher<ByteBuffer> publisher = subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onComplete();
                }

                @Override public void cancel() {}
            });
        };

        var response = HttpResponse.status(HttpStatus.OK)
                .body(ResponseBody.stream(publisher, 100L));

        writer.write(context, response, false, ignored -> completed.set(true));

        io.netty.handler.codec.http.HttpResponse initial = channel.readOutbound();
        assertNotNull(initial);
        assertEquals("100", initial.headers().get(HttpHeaderNames.CONTENT_LENGTH));
    }

    @Test
    void handlesNullResponseBodyTypeGracefully() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var writer = new NettyResponseWriter();
        var completed = new AtomicBoolean();

        var response = HttpResponse.status(HttpStatus.OK).body((ResponseBody) null);
        writer.write(context, response, true, ignored -> completed.set(true));

        assertTrue(completed.get());
        assertFalse(channel.isOpen());
    }

    @Test
    void encodesAValueBodyWithTheConfiguredCodec() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var result = new AtomicReference<WriteResult>();

        new NettyResponseWriter(java.time.Duration.ofSeconds(30), upperCaseCodec())
                .write(context, HttpMethod.GET, HttpResponse.value("payload"), true, result::set);

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals("PAYLOAD", response.content().toString(StandardCharsets.UTF_8));
        assertEquals(7, response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals("text/upper", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertTrue(result.get() instanceof WriteResult.Success);
        response.release();
    }

    @Test
    void headOnAValueBodyReportsTheEncodedLengthWithoutBodyBytes() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);

        new NettyResponseWriter(java.time.Duration.ofSeconds(30), upperCaseCodec())
                .write(context, HttpMethod.HEAD, HttpResponse.value("payload"), true,
                        ignored -> {});

        FullHttpResponse response = channel.readOutbound();
        assertEquals(7, response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, response.content().readableBytes());
        response.release();
    }

    @Test
    void aValueBodyWithoutAConfiguredCodecFailsInsteadOfWritingGarbage() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);
        var result = new AtomicReference<WriteResult>();

        new NettyResponseWriter().write(context, HttpMethod.GET,
                HttpResponse.value("payload"), true, result::set);

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
        assertTrue(result.get() instanceof WriteResult.Success);
        response.release();
    }

    @Test
    void aFailingCodecProducesAnErrorResponseInsteadOfEscaping() {
        var handler = new io.netty.channel.ChannelInboundHandlerAdapter();
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);

        var throwing = new io.waveio.http.body.BodyCodec() {
            @Override public String contentType() { return "text/upper"; }
            @Override public byte[] encode(Object value) {
                throw new IllegalStateException("cannot encode");
            }
            @Override public <T> T decode(byte[] body, Class<T> type) { return null; }
        };

        new NettyResponseWriter(java.time.Duration.ofSeconds(30), throwing)
                .write(context, HttpMethod.GET, HttpResponse.value("payload"), true,
                        ignored -> {});

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
        response.release();
    }

    private static io.waveio.http.body.BodyCodec upperCaseCodec() {
        return new io.waveio.http.body.BodyCodec() {
            @Override public String contentType() { return "text/upper"; }
            @Override public byte[] encode(Object value) {
                return value.toString().toUpperCase(java.util.Locale.ROOT)
                        .getBytes(StandardCharsets.UTF_8);
            }
            @Override public <T> T decode(byte[] body, Class<T> type) {
                return type.cast(new String(body, StandardCharsets.UTF_8));
            }
        };
    }
}
