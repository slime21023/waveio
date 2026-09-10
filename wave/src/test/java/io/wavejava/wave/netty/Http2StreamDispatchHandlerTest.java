package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.wavejava.wave.api.http.Http2Config;
import io.wavejava.wave.api.observability.Observability;
import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import io.wavejava.wave.api.server.RequestBodyMode;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.runtime.InvocationRuntime;
import io.wavejava.wave.runtime.ObservabilityDispatcher;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class Http2StreamDispatchHandlerTest {
    @Test
    void rejectsIncompleteAndConnectionSpecificHeadersBeforeInvocation() {
        try (var runtime = new InvocationRuntime()) {
            var observability = new ObservabilityDispatcher(Observability.disabled());
            try {
                var invoked = new AtomicBoolean();
                var incomplete = new EmbeddedChannel(handler(runtime, observability, invoked));
                try {
                    incomplete.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
                    var response = (HttpResponse) incomplete.readOutbound();
                    assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
                    assertFalse(invoked.get());
                } finally {
                    incomplete.finishAndReleaseAll();
                }

                var unsafeHeaders = new EmbeddedChannel(handler(runtime, observability, invoked));
                try {
                    var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.EMPTY_BUFFER);
                    request.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
                    unsafeHeaders.writeInbound(request);
                    var response = (HttpResponse) unsafeHeaders.readOutbound();
                    assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
                    assertFalse(invoked.get());
                } finally {
                    unsafeHeaders.finishAndReleaseAll();
                }
            } finally {
                observability.close(java.time.Duration.ZERO);
            }
        }
    }

    private static Http2StreamDispatchHandler handler(
            InvocationRuntime runtime, ObservabilityDispatcher observability, AtomicBoolean invoked) {
        var limits = ServerLimits.defaults();
        return new Http2StreamDispatchHandler(
                (request, routeObserver) -> {
                    invoked.set(true);
                    throw new AssertionError("invalid HTTP/2 input must not reach dispatch");
                },
                runtime,
                limits,
                ServerTimeouts.defaults(),
                new Semaphore(limits.maximumInFlightRequests()),
                new Http2ConnectionState(Http2Config.builder().build(), limits),
                observability,
                ForwardedHeaderPolicy.disabled(),
                RequestBodyMode.AGGREGATED);
    }
}
