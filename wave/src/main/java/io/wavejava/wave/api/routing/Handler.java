package io.wavejava.wave.api.routing;

import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;

/**
 * Handles one matched HTTP request.
 *
 * <p>Handlers use direct, synchronous Java control flow. The runtime is responsible for invoking
 * them away from the transport event loop and for mapping a thrown exception before the response
 * is committed.</p>
 */
@FunctionalInterface
public interface Handler {
    /**
     * Handles a request and writes its response.
     *
     * @param request the immutable request view, including any matched path parameters
     * @param response the invocation-owned response builder
     * @throws Exception when application handling cannot complete
     */
    void handle(Request request, Response response) throws Exception;
}
