package io.waveio.http.internal.netty;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.internal.body.InboundBodyPublisher;
import io.waveio.http.internal.dispatch.RequestDispatcher;
import io.waveio.http.routing.RouteMetadata;
import io.waveio.http.routing.Router;
import io.waveio.http.server.RequestObservation;
import io.waveio.http.server.RequestObserver;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One ordered HTTP/1.1 request/response exchange.
 *
 * <p>All mutable state is owned by the channel event loop and is reached only through the named
 * transitions below; completions arriving from other threads are rescheduled onto that loop before
 * they touch an exchange. {@link #claim()} is the single atomic gate shared by normal completion,
 * the handler deadline and disconnection — only its winner may produce a response.
 */
final class Exchange {
    private static final System.Logger LOG = System.getLogger(Exchange.class.getName());

    private enum State { NEW, DISPATCHING, RESPONSE_READY, WRITING, COMPLETED, DISCONNECTED }

    private final HttpRequest request;
    private final boolean keepAlive;
    private final boolean asynchronous;
    private final Optional<String> pathPattern;
    private final RouteMetadata metadata;
    private final InboundBodyPublisher bodyPublisher;
    private final long receivedNanos = System.nanoTime();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean observed = new AtomicBoolean();
    private volatile long deadlineNanos = Long.MAX_VALUE;
    private volatile RequestDispatcher.DispatchHandle dispatch;
    private volatile ScheduledFuture<?> timeout;
    private volatile Throwable failure;
    private HttpResponse response;
    private State state = State.NEW;
    private boolean started;
    private boolean timedOut;
    private boolean bodyComplete;
    private boolean forceClose;

    Exchange(HttpRequest request, boolean keepAlive, Optional<Router.Match> match,
            InboundBodyPublisher bodyPublisher, boolean bodyComplete) {
        this.request = request;
        this.keepAlive = keepAlive;
        this.bodyPublisher = bodyPublisher;
        this.bodyComplete = bodyComplete;
        asynchronous = match.map(Router.Match::asynchronous).orElse(false);
        pathPattern = match.map(Router.Match::pathPattern);
        metadata = match.map(Router.Match::metadata).orElse(RouteMetadata.empty());
    }

    HttpRequest request() {
        return request;
    }

    HttpResponse response() {
        return response;
    }

    InboundBodyPublisher bodyPublisher() {
        return bodyPublisher;
    }

    boolean asynchronous() {
        return asynchronous;
    }

    boolean started() {
        return started;
    }

    /** Keep-alive survives only when nothing forced the connection to close. */
    boolean keepAlive() {
        return keepAlive && !forceClose;
    }

    boolean ownsBody(InboundBodyPublisher publisher) {
        return bodyPublisher == publisher;
    }

    /** True while this exchange still owns an inbound stream that has not terminated. */
    boolean awaitingBody() {
        return bodyPublisher != null && !bodyComplete;
    }

    /** A response may only be written once it exists and the request body can no longer arrive. */
    boolean readyToWrite() {
        return response != null && bodyComplete;
    }

    // --- dispatch ---------------------------------------------------------------------------

    void beginDispatch(Duration handlerTimeout) {
        started = true;
        transition(State.NEW, State.DISPATCHING);
        long now = System.nanoTime();
        long timeoutNanos = ConnectionOptions.nanos(handlerTimeout);
        deadlineNanos = timeoutNanos == Long.MAX_VALUE || Long.MAX_VALUE - now < timeoutNanos
                ? Long.MAX_VALUE : now + timeoutNanos;
    }

    void scheduleTimeout(ScheduledFuture<?> value) {
        timeout = value;
    }

    void attach(RequestDispatcher.DispatchHandle value) {
        dispatch = value;
        if (finished.get()) value.cancel();
    }

    /**
     * Claims the sole right to finish this exchange. An event-loop deadline cannot preempt a
     * handler occupying that same loop, so a late result is rejected by {@link #expired()} rather
     * than by the timer alone.
     */
    boolean claim() {
        if (!finished.compareAndSet(false, true)) return false;
        var timer = timeout;
        if (timer != null) timer.cancel(false);
        return true;
    }

    boolean expired() {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0;
    }

    void cancelDispatch() {
        var current = dispatch;
        if (current != null) current.cancel();
    }

    void cancel() {
        if (!finished.compareAndSet(false, true)) return;
        var timer = timeout;
        if (timer != null) timer.cancel(false);
        cancelDispatch();
        if (bodyPublisher != null) bodyPublisher.disconnect();
    }

    // --- outcomes ---------------------------------------------------------------------------

    void respondWith(HttpResponse value) {
        if (state != State.NEW && state != State.DISPATCHING) {
            throw new IllegalStateException("Exchange cannot become response-ready from " + state);
        }
        response = value;
        state = State.RESPONSE_READY;
    }

    void recordFailure(Throwable value) {
        failure = value;
    }

    void markTimedOut() {
        timedOut = true;
    }

    /**
     * Terminates an unread request body. Unread inbound bytes make keep-alive unsafe, so the
     * connection is closed after this exchange's response.
     */
    void abandonBody(Throwable reason) {
        if (!awaitingBody()) return;
        bodyPublisher.fail(reason);
        discardBody();
    }

    /** Marks the body finished without failing the publisher, which already terminated itself. */
    void discardBody() {
        bodyComplete = true;
        forceClose = true;
    }

    void bodyCompleted() {
        bodyComplete = true;
    }

    void beginWrite() {
        transition(State.RESPONSE_READY, State.WRITING);
    }

    void finishWrite() {
        transition(State.WRITING, State.COMPLETED);
    }

    void disconnected() {
        if (state != State.COMPLETED) state = State.DISCONNECTED;
    }

    // --- observation ------------------------------------------------------------------------

    RequestObservation.Outcome outcome() {
        if (timedOut) return RequestObservation.Outcome.TIMEOUT;
        return failure != null ? RequestObservation.Outcome.FAILURE
                : RequestObservation.Outcome.SUCCESS;
    }

    /**
     * Emits at most one observation. Timeout, write completion and disconnection race for this
     * latch, so a single exchange can never produce duplicate metrics events.
     */
    void observe(List<RequestObserver> observers, RequestObservation.Outcome outcome,
            Optional<io.waveio.http.HttpStatus> status) {
        if (!observed.compareAndSet(false, true)) return;
        long elapsedNanos = Math.max(0, System.nanoTime() - receivedNanos);
        var observation = new RequestObservation(request, pathPattern, metadata, outcome, status,
                Optional.ofNullable(failure), Duration.ofNanos(elapsedNanos));
        for (var observer : observers) {
            try {
                observer.onComplete(observation);
            } catch (Throwable observerFailure) {
                LOG.log(System.Logger.Level.TRACE,
                        "Request observer threw; the connection is unaffected", observerFailure);
                // Observer failures must not affect the connection.
            }
        }
    }

    private void transition(State expected, State next) {
        if (state != expected) {
            throw new IllegalStateException(
                    "Invalid exchange transition " + state + " -> " + next);
        }
        state = next;
    }
}
