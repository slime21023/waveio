package io.waveio.http.internal.netty;

import io.waveio.http.internal.body.InboundBodyPublisher;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * The bounded, strictly ordered exchange queue of one HTTP/1.1 connection.
 *
 * <p>Only the head may write, so responses leave in request order regardless of the order in which
 * handlers complete. The bound counts the active exchange plus the pending ones behind it; one
 * request beyond that is an already-decoded batch overflow that no longer has a response-order-safe
 * answer, which {@link #overflowed()} reports to the caller.
 */
final class ExchangeQueue {
    private final ArrayDeque<Exchange> exchanges = new ArrayDeque<>();
    private final int capacity;

    ExchangeQueue(int maxPendingRequests) {
        capacity = maxPendingRequests + 1;
    }

    boolean isEmpty() {
        return exchanges.isEmpty();
    }

    /** True when another exchange cannot be accepted without breaking the configured bound. */
    boolean overflowed() {
        return exchanges.size() >= capacity;
    }

    void add(Exchange exchange) {
        exchanges.addLast(exchange);
    }

    Exchange head() {
        return exchanges.peekFirst();
    }

    Exchange removeHead() {
        return exchanges.pollFirst();
    }

    /** The most recently accepted exchange, or {@code null} when the connection is idle. */
    Exchange tail() {
        return exchanges.peekLast();
    }

    boolean contains(Exchange exchange) {
        return exchanges.contains(exchange);
    }

    Exchange withBody(InboundBodyPublisher publisher) {
        for (var exchange : exchanges) {
            if (exchange.ownsBody(publisher)) return exchange;
        }
        return null;
    }

    /** True while any queued exchange still owns an inbound stream that has not terminated. */
    boolean hasIncomingBody() {
        for (var exchange : exchanges) {
            if (exchange.awaitingBody()) return true;
        }
        return false;
    }

    void forEach(Consumer<Exchange> action) {
        exchanges.forEach(action);
    }

    void clear() {
        exchanges.clear();
    }
}
