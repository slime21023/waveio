package io.waveio.http.handler;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Functional interface for handling HTTP requests with inbound streaming request bodies.
 *
 * <p>Registered via streaming routing methods (e.g., {@code streamingPost}, {@code streamingPut}).
 * Receives the request headers together with a {@link Flow.Publisher} that emits inbound body
 * chunks as {@link ByteBuffer} instances as they arrive from the network socket.
 *
 * <p>Socket reading automatically throttles based on reactive demand signaled through
 * {@link java.util.concurrent.Flow.Subscription#request(long)}.
 *
 * @see HttpHandler
 * @see AsyncHttpHandler
 */
@FunctionalInterface
public interface StreamingHttpHandler {

    /**
     * Processes a streaming HTTP request body.
     *
     * @param request the immutable HTTP request metadata
     * @param body reactive publisher emitting inbound payload byte buffers
     * @return a {@link CompletionStage} yielding the HTTP response upon completion
     * @throws Exception if handler setup fails
     */
    CompletionStage<HttpResponse> handle(HttpRequest request,
            Flow.Publisher<ByteBuffer> body) throws Exception;
}
