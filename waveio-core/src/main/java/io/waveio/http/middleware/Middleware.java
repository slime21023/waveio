package io.waveio.http.middleware;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;

/**
 * Interceptor in the HTTP request/response processing pipeline.
 *
 * <p>Middleware can inspect, wrap, or modify incoming requests before passing them downstream,
 * inspect and alter outgoing responses, short-circuit execution by returning a response directly,
 * or handle/map downstream errors.
 *
 * <p><b>Execution Order:</b> Middleware registered globally on {@code HttpServerBuilder.use(...)}
 * executes outermost, followed by route-scoped middleware registered via {@code RouteRegistry.use(...)}.
 *
 * <pre>{@code
 * Middleware loggingMiddleware = (request, chain) -> {
 *     long start = System.currentTimeMillis();
 *     HttpResponse response = chain.next(request);
 *     long duration = System.currentTimeMillis() - start;
 *     System.out.println(request.method() + " " + request.path() + " -> " + response.status().code() + " (" + duration + "ms)");
 *     return response;
 * };
 * }</pre>
 *
 * @see HandlerChain
 */
@FunctionalInterface
public interface Middleware {

    /**
     * Intercepts an HTTP request and coordinates its downstream execution.
     *
     * @param request the current HTTP request
     * @param chain continuation to invoke the next middleware or final handler
     * @return the HTTP response to return
     * @throws Exception if processing fails
     */
    HttpResponse handle(HttpRequest request, HandlerChain chain) throws Exception;
}
