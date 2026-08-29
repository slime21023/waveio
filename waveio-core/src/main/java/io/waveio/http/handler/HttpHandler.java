package io.waveio.http.handler;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;

/**
 * Functional interface for processing HTTP requests synchronously.
 *
 * <p><b>Threading &amp; Concurrency Guarantees:</b>
 * <ul>
 *   <li><b>Non-blocking routes:</b> When registered with standard methods (e.g., {@code get},
 *       {@code post}), this handler executes directly on the Netty I/O EventLoop thread.
 *       Implementations <b>MUST NOT</b> execute blocking I/O, long loops, or synchronous database
 *       calls on this thread, as doing so will starve the event loop for all multiplexed connections.</li>
 *   <li><b>Blocking routes:</b> When registered with blocking methods (e.g., {@code blockingGet},
 *       {@code blockingPost}), this handler executes on lightweight Java 21 Virtual Threads and is
 *       safe to perform synchronous blocking operations without affecting network I/O throughput.</li>
 * </ul>
 *
 * @see AsyncHttpHandler
 * @see StreamingHttpHandler
 */
@FunctionalInterface
public interface HttpHandler {

    /**
     * Processes an HTTP request and returns an HTTP response.
     *
     * @param request the immutable HTTP request
     * @return the HTTP response to send to the client
     * @throws Exception if any processing error occurs; mapped by the server's exception handler
     */
    HttpResponse handle(HttpRequest request) throws Exception;
}
