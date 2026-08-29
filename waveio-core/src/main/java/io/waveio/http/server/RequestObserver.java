package io.waveio.http.server;

/**
 * Callback SPI for observing completed HTTP requests.
 *
 * <p>Observers are notified upon the terminal completion of every request lifecycle (including
 * successful responses, unhandled failures, timeouts, and early client disconnects).
 *
 * <p><b>Fault Isolation:</b> Any exception thrown within an observer callback is caught and logged
 * without interrupting the Netty channel lifecycle.
 *
 * @see RequestObservation
 * @see HttpServerBuilder#observe(RequestObserver)
 */
@FunctionalInterface
public interface RequestObserver {

    /**
     * Invoked when an HTTP request lifecycle reaches a terminal outcome.
     *
     * @param observation immutable observation details
     */
    void onComplete(RequestObservation observation);
}
