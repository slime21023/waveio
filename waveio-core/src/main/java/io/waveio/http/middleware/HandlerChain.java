package io.waveio.http.middleware;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;

/**
 * Represents the continuation of the HTTP processing pipeline.
 *
 * <p>Passed to {@link Middleware} instances to allow invoking the next middleware or the
 * terminal endpoint handler in the chain.
 *
 * @see Middleware
 */
@FunctionalInterface
public interface HandlerChain {

    /**
     * Advances execution to the next middleware or target endpoint handler.
     *
     * @param request the current (or modified) HTTP request
     * @return the HTTP response generated downstream
     * @throws Exception if downstream execution fails
     */
    HttpResponse next(HttpRequest request) throws Exception;
}
