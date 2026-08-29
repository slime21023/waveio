package io.waveio.http.internal.netty;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.internal.body.InboundBodyPublisher;

sealed interface NettyInboundMessage {
    record Buffered(HttpRequest request, boolean keepAlive) implements NettyInboundMessage {}
    record StreamingStart(HttpRequest request, boolean keepAlive,
            InboundBodyPublisher body) implements NettyInboundMessage {}
    record StreamingComplete(InboundBodyPublisher body) implements NettyInboundMessage {}
    record StreamingFailure(InboundBodyPublisher body, Throwable failure)
            implements NettyInboundMessage {}
    record Rejected(HttpRequest request, HttpResponse response) implements NettyInboundMessage {}

    /**
     * Announces an {@code Expect: 100-continue} request head. The interim response is emitted by
     * the exchange queue so it cannot overtake an earlier pending final response.
     */
    record ContinueExpected() implements NettyInboundMessage {}
}
