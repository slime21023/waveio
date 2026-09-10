package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.api.observability.Observability;
import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import io.wavejava.wave.api.server.RequestBodyMode;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.runtime.ApplicationResult;
import io.wavejava.wave.runtime.InvocationRuntime;
import io.wavejava.wave.runtime.ObservabilityDispatcher;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RequestDispatchHandlerTest {
    @Test
    void rejectsIncompleteOrSmugglingProneRequestsBeforeApplicationDispatch() {
        try (var runtime = new InvocationRuntime()) {
            var observability = new ObservabilityDispatcher(Observability.disabled());
            var invoked = new AtomicBoolean();
            var channel = new EmbeddedChannel(handler(runtime, observability, request -> {
                invoked.set(true);
                return new ApplicationResult(new io.wavejava.wave.internal.http.InternalResponse().text("unexpected"),
                        Outcome.success(), "/");
            }));
            try {
                var smuggling = request("/");
                smuggling.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
                smuggling.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
                channel.writeInbound(smuggling);
                channel.runPendingTasks();
                var badRequest = (FullHttpResponse) channel.readOutbound();
                try {
                    assertEquals(HttpResponseStatus.BAD_REQUEST, badRequest.status());
                } finally {
                    badRequest.release();
                }
                assertFalse(invoked.get());
            } finally {
                channel.finishAndReleaseAll();
                observability.close(java.time.Duration.ZERO);
            }
        }
    }

    private static RequestDispatchHandler handler(
            InvocationRuntime runtime,
            ObservabilityDispatcher observability,
            java.util.function.Function<io.wavejava.wave.api.http.Request, ApplicationResult> dispatch) {
        var limits = ServerLimits.defaults();
        return new RequestDispatchHandler(
                (request, routeObserver) -> {
                    routeObserver.accept("/test");
                    return dispatch.apply(request);
                },
                runtime,
                limits,
                ServerTimeouts.defaults(),
                new Semaphore(limits.maximumInFlightRequests()),
                RequestBodyMode.AGGREGATED,
                observability,
                ForwardedHeaderPolicy.disabled());
    }

    private static DefaultFullHttpRequest request(String target) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, target, Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.HOST, "wave.test");
        return request;
    }
}
