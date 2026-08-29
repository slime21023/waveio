package io.waveio.http.server;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;

/**
 * Functional contract for transforming unhandled server exceptions into HTTP responses.
 *
 * <p>By default, WaveIO uses a built-in exception mapper:
 * <ul>
 *   <li>{@link java.util.concurrent.TimeoutException} is mapped to {@code 504 Gateway Timeout}.</li>
 *   <li>{@link io.waveio.http.HttpException} is mapped to its contained {@link io.waveio.http.HttpStatus}.</li>
 *   <li>Any other {@link Throwable} is mapped to {@code 500 Internal Server Error}.</li>
 * </ul>
 *
 * @see HttpServerBuilder#exceptionHandler(ExceptionHandler)
 */
@FunctionalInterface
public interface ExceptionHandler {

    /**
     * Maps an uncaught failure into an HTTP response.
     *
     * @param failure the uncaught exception or error
     * @param request the HTTP request during which the error occurred (may be null if decoding failed early)
     * @return the HTTP response to send to the client
     */
    HttpResponse handle(Throwable failure, HttpRequest request);
}
