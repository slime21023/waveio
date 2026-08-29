package io.waveio.http.handler;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import java.util.concurrent.CompletionStage;

/**
 * Functional interface for processing HTTP requests asynchronously via reactive stage pipelines.
 *
 * <p>Registered with asynchronous routing methods (e.g., {@code asyncGet}, {@code asyncPost}).
 * Handlers return a {@link CompletionStage} that can complete on any thread pool.
 * WaveIO guarantees that out-of-order completions are re-sequenced to preserve RFC 7230
 * HTTP/1.1 pipelining order before sending bytes over the socket.
 *
 * @see HttpHandler
 * @see StreamingHttpHandler
 */
@FunctionalInterface
public interface AsyncHttpHandler {

    /**
     * Processes an incoming request and returns a future stage that completes with the response.
     *
     * @param request the immutable HTTP request
     * @return a {@link CompletionStage} yielding the HTTP response
     * @throws Exception if synchronous invocation fails before returning a stage
     */
    CompletionStage<HttpResponse> handle(HttpRequest request) throws Exception;
}
