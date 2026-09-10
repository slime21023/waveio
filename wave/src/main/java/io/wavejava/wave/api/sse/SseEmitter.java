package io.wavejava.wave.api.sse;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single-subscriber, bounded publisher of {@link SseEvent SSE events}.
 *
 * <p>An emitter is attached to a handler response through {@link #writeTo(Request, Response)}.
 * The existing response Flow transport then owns demand and copies the UTF-8 bytes at its Netty
 * boundary. This class owns only encoded Java byte arrays and never exposes a Netty type.</p>
 *
 * <p>Queued events are bounded by both count and byte size. On overflow the emitter fails its
 * Flow subscriber with {@link SseOverflowException}, deliberately closing the slow subscriber
 * rather than retaining an unbounded backlog. Request cancellation schedules a terminal Flow
 * signal on an off-transport lane; it never invokes an application callback on a transport
 * thread.</p>
 */
public final class SseEmitter implements Flow.Publisher<ByteBuffer>, AutoCloseable {
    /** Default maximum events waiting for Flow demand from one subscriber. */
    public static final int DEFAULT_MAXIMUM_QUEUED_EVENTS = 64;

    /** Default maximum encoded UTF-8 bytes waiting for Flow demand from one subscriber. */
    public static final long DEFAULT_MAXIMUM_QUEUED_BYTES = 64L * 1024L;

    /** Result of trying to enqueue one event. */
    public enum Emission {
        /** The event was accepted into the finite queue. */
        ACCEPTED,
        /** The emitter was already terminal and did not accept the event. */
        REJECTED_CLOSED,
        /** The event would exceed a budget and therefore terminated the emitter. */
        REJECTED_OVERFLOW
    }

    /** Observable emitter lifecycle. */
    public enum State {
        /** The emitter can accept events. */
        OPEN,
        /** Graceful close requested; queued events will be delivered before completion. */
        CLOSING,
        /** Gracefully completed after its queue drained. */
        CLOSED,
        /** Cancelled by the transport, request, or an explicit abort. */
        CANCELLED,
        /** Failed because a bounded queue or Flow contract was violated. */
        FAILED
    }

    /** Immutable, deterministic inspection snapshot for tests and operational probes. */
    public record Snapshot(
            State state,
            boolean subscriberAttached,
            int queuedEvents,
            long queuedBytes,
            int maximumQueuedEvents,
            long maximumQueuedBytes,
            boolean heartbeatScheduled) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            if (queuedEvents < 0 || queuedBytes < 0 || maximumQueuedEvents <= 0 || maximumQueuedBytes <= 0) {
                throw new IllegalArgumentException("SSE queue snapshot contains an invalid budget");
            }
            if (queuedEvents > maximumQueuedEvents || queuedBytes > maximumQueuedBytes) {
                throw new IllegalArgumentException("SSE queue snapshot exceeds its configured budget");
            }
        }
    }

    private final Object monitor = new Object();
    private final int maximumQueuedEvents;
    private final long maximumQueuedBytes;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeatScheduler;
    private final ArrayDeque<EncodedEvent> queue = new ArrayDeque<>();

    private Flow.Subscriber<? super ByteBuffer> subscriber;
    private boolean subscriberAttached;
    private boolean subscriberReady;
    private boolean responseBound;
    private long queuedBytes;
    private long demand;
    private boolean draining;
    private boolean terminalSignalSent;
    private boolean signalCancellation;
    private final AtomicBoolean terminalDrainScheduled = new AtomicBoolean();
    private State state = State.OPEN;
    private Throwable terminalFailure;
    private ScheduledFuture<?> heartbeatTask;
    private CancellationToken.Registration cancellationRegistration;

    private SseEmitter(Builder builder) {
        maximumQueuedEvents = builder.maximumQueuedEvents;
        maximumQueuedBytes = builder.maximumQueuedBytes;
        heartbeatInterval = builder.heartbeatInterval;
        heartbeatScheduler = builder.heartbeatScheduler;
    }

    /** Creates an emitter with the documented finite default budgets and no automatic heartbeat. */
    public static SseEmitter create() {
        return builder().build();
    }

    /** Starts a builder for an emitter with finite queue budgets. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Attaches this emitter to exactly one SSE response.
     *
     * <p>The method sets UTF-8 event-stream media type and a no-cache default without overwriting
     * a caller-provided {@code Cache-Control}. A supplied request context is observed for
     * disconnect, deadline, and shutdown cancellation. A cancellation preserves a terminal signal
     * even if it races before the response Flow subscription, and dispatches that signal away from
     * a Netty EventLoop.</p>
     *
     * @throws IllegalStateException if this emitter is already attached or the response declares a
     *         non-SSE content type
     */
    public void writeTo(Request request, Response response) {
        var sourceRequest = Objects.requireNonNull(request, "request");
        var targetResponse = Objects.requireNonNull(response, "response");
        validateResponseContentType(targetResponse);

        synchronized (monitor) {
            if (responseBound) {
                throw new IllegalStateException("An SSE emitter may be attached to only one response");
            }
            responseBound = true;
        }

        CancellationToken.Registration registration = null;
        try {
            registration = sourceRequest.context()
                    .map(context -> context.cancellationToken().onCancellation(ignored -> cancelFromRequest()))
                    .orElse(null);
            synchronized (monitor) {
                cancellationRegistration = registration;
                if (state != State.OPEN) {
                    closeRegistrationLocked();
                }
            }

            targetResponse.header("Content-Type", MediaType.TEXT_EVENT_STREAM
                    .withCharset(StandardCharsets.UTF_8).toString());
            if (!targetResponse.headers().contains("Cache-Control")) {
                targetResponse.header("Cache-Control", "no-cache");
            }
            targetResponse.stream(this);
        } catch (RuntimeException failure) {
            if (registration != null) {
                registration.close();
            }
            synchronized (monitor) {
                if (cancellationRegistration == registration) {
                    cancellationRegistration = null;
                }
                responseBound = false;
            }
            throw failure;
        }
    }

    /**
     * Attempts to publish one event.
     *
     * <p>The event is encoded before it enters the queue, so both count and byte budgets cover
     * the exact wire representation. A rejected overflow makes this emitter terminal and signals
     * the response Flow subscriber so the affected connection is closed.</p>
     */
    public Emission emit(SseEvent event) {
        var encoded = new EncodedEvent(Objects.requireNonNull(event, "event").encode());
        final boolean overflow;
        synchronized (monitor) {
            if (state != State.OPEN) {
                return Emission.REJECTED_CLOSED;
            }
            overflow = queue.size() == maximumQueuedEvents
                    || encoded.bytes().length > maximumQueuedBytes - queuedBytes;
            if (!overflow) {
                queue.addLast(encoded);
                queuedBytes += encoded.bytes().length;
            } else {
                terminateLocked(State.FAILED, new SseOverflowException(maximumQueuedEvents, maximumQueuedBytes), false);
            }
        }
        if (overflow) {
            drain();
            return Emission.REJECTED_OVERFLOW;
        }
        drain();
        return Emission.ACCEPTED;
    }

    /**
     * Requests a graceful completion after already queued events have been delivered.
     *
     * @return {@code true} when this call initiated closing
     */
    public boolean complete() {
        synchronized (monitor) {
            if (state != State.OPEN) {
                return false;
            }
            state = State.CLOSING;
            cancelHeartbeatLocked();
            closeRegistrationLocked();
        }
        drain();
        return true;
    }

    /** Equivalent to {@link #complete()}, suitable for try-with-resources cleanup. */
    @Override
    public void close() {
        complete();
    }

    /**
     * Immediately drops queued data and fails the response stream, causing its transport to close
     * the affected connection.
     *
     * @return {@code true} when this call chose the terminal cancellation state
     */
    public boolean abort() {
        synchronized (monitor) {
            if (state != State.OPEN && state != State.CLOSING) {
                return false;
            }
            terminateLocked(State.CANCELLED, new CancellationException("SSE emitter aborted"), true);
        }
        drain();
        return true;
    }

    /** Returns a deterministic snapshot without exposing queued event bytes. */
    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(
                    state,
                    subscriberAttached,
                    queue.size(),
                    queuedBytes,
                    maximumQueuedEvents,
                    maximumQueuedBytes,
                    heartbeatTask != null && !heartbeatTask.isDone());
        }
    }

    /** Returns this emitter's current lifecycle state. */
    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> target) {
        var candidate = Objects.requireNonNull(target, "subscriber");
        final EmitterSubscription subscription;
        final boolean rejected;
        synchronized (monitor) {
            if (subscriberAttached) {
                subscription = null;
                rejected = true;
            } else {
                subscriberAttached = true;
                subscriber = candidate;
                subscription = new EmitterSubscription();
                rejected = false;
            }
        }
        if (rejected) {
            rejectAdditionalSubscriber(candidate);
            return;
        }

        try {
            candidate.onSubscribe(subscription);
        } catch (Throwable ignored) {
            subscription.cancel();
            return;
        }
        synchronized (monitor) {
            subscriberReady = true;
            if (state == State.OPEN) {
                startHeartbeatLocked();
            }
        }
        drain();
    }

    private void request(long requested) {
        if (requested <= 0) {
            fail(new IllegalArgumentException("SSE Flow demand must be greater than zero"));
            return;
        }
        synchronized (monitor) {
            if (state != State.OPEN && state != State.CLOSING) {
                return;
            }
            demand = addDemand(demand, requested);
        }
        drain();
    }

    private void cancelFromSubscriber() {
        synchronized (monitor) {
            if (state == State.CLOSED || state == State.CANCELLED || state == State.FAILED) {
                return;
            }
            terminateLocked(State.CANCELLED, null, false);
        }
    }

    /**
     * Cancellation-token callbacks can be invoked by a Netty EventLoop. Preserve a terminal signal
     * for a subscriber which has not been attached yet, but route any actual callback through the
     * shared off-transport terminal lane. This closes the pre-subscribe cancellation race without
     * allowing a lifecycle hook to run subscriber/application code on an EventLoop.
     */
    private void cancelFromRequest() {
        synchronized (monitor) {
            if (state == State.CLOSED || state == State.CANCELLED || state == State.FAILED) {
                return;
            }
            terminateLocked(State.CANCELLED, new CancellationException("SSE request cancelled"), true);
        }
        scheduleTerminalDrain();
    }

    private void fail(Throwable failure) {
        synchronized (monitor) {
            if (state == State.CLOSED || state == State.CANCELLED || state == State.FAILED) {
                return;
            }
            terminateLocked(State.FAILED, Objects.requireNonNull(failure, "failure"), false);
        }
        drain();
    }

    private void drain() {
        synchronized (monitor) {
            if (draining || !subscriberReady) {
                return;
            }
            draining = true;
        }

        while (true) {
            var delivery = nextDelivery();
            if (delivery == null) {
                return;
            }
            try {
                if (delivery.bytes() != null) {
                    subscriber.onNext(ByteBuffer.wrap(delivery.bytes()).asReadOnlyBuffer());
                } else if (delivery.failure() != null) {
                    subscriber.onError(delivery.failure());
                } else {
                    subscriber.onComplete();
                }
            } catch (Throwable ignored) {
                synchronized (monitor) {
                    terminateLocked(State.CANCELLED, null, false);
                    draining = false;
                }
                return;
            }
            if (delivery.bytes() == null) {
                synchronized (monitor) {
                    draining = false;
                }
                return;
            }
        }
    }

    /** Schedules at most one cancellation terminal drain outside a transport callback. */
    private void scheduleTerminalDrain() {
        if (!terminalDrainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    drain();
                } finally {
                    terminalDrainScheduled.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A late subscriber still calls drain() after onSubscribe. The normal server close
            // path independently cancels the transport Flow subscription, so rejection cannot
            // retain queued event bytes or keep a live connection open.
            terminalDrainScheduled.set(false);
        }
    }

    private Delivery nextDelivery() {
        synchronized (monitor) {
            if (state == State.OPEN || state == State.CLOSING) {
                if (demand > 0 && !queue.isEmpty()) {
                    var next = queue.removeFirst();
                    queuedBytes -= next.bytes().length;
                    demand--;
                    return Delivery.bytes(next.bytes());
                }
                if (state == State.CLOSING && queue.isEmpty()) {
                    state = State.CLOSED;
                    cancelHeartbeatLocked();
                    closeRegistrationLocked();
                    terminalSignalSent = true;
                    return Delivery.complete();
                }
                draining = false;
                return null;
            }
            if ((state == State.FAILED || state == State.CANCELLED)
                    && signalCancellation
                    && !terminalSignalSent) {
                terminalSignalSent = true;
                return Delivery.failure(terminalFailure == null
                        ? new CancellationException("SSE emitter cancelled")
                        : terminalFailure);
            }
            draining = false;
            return null;
        }
    }

    private void terminateLocked(State terminalState, Throwable failure, boolean notifySubscriber) {
        state = terminalState;
        terminalFailure = failure;
        signalCancellation = notifySubscriber || terminalState == State.FAILED;
        queue.clear();
        queuedBytes = 0;
        demand = 0;
        cancelHeartbeatLocked();
        closeRegistrationLocked();
    }

    private void startHeartbeatLocked() {
        if (heartbeatInterval == null || heartbeatTask != null || state != State.OPEN) {
            return;
        }
        try {
            var intervalNanos = toNanosSaturated(heartbeatInterval);
            heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                    () -> emit(SseEvent.heartbeat()),
                    intervalNanos,
                    intervalNanos,
                    TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException rejection) {
            terminateLocked(State.FAILED, rejection, false);
        }
    }

    private void cancelHeartbeatLocked() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private void closeRegistrationLocked() {
        if (cancellationRegistration != null) {
            cancellationRegistration.close();
            cancellationRegistration = null;
        }
    }

    private static void validateResponseContentType(Response response) {
        var existing = response.headers().first("Content-Type");
        if (existing.isEmpty()) {
            return;
        }
        final MediaType parsed;
        try {
            parsed = MediaType.parse(existing.get());
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("SSE response Content-Type is invalid", failure);
        }
        if (!MediaType.TEXT_EVENT_STREAM.equals(parsed.withoutParameter("charset"))) {
            throw new IllegalStateException("SSE response must use text/event-stream Content-Type");
        }
        parsed.charset().ifPresent(charset -> {
            if (!StandardCharsets.UTF_8.equals(charset)) {
                throw new IllegalStateException("SSE response charset must be UTF-8");
            }
        });
    }

    private static void rejectAdditionalSubscriber(Flow.Subscriber<? super ByteBuffer> target) {
        target.onSubscribe(NoopSubscription.INSTANCE);
        target.onError(new IllegalStateException("An SSE emitter supports only one subscriber"));
    }

    private static long addDemand(long current, long added) {
        if (Long.MAX_VALUE - current < added) {
            return Long.MAX_VALUE;
        }
        return current + added;
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record EncodedEvent(byte[] bytes) {
        private EncodedEvent {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length == 0) {
                throw new IllegalArgumentException("SSE wire event must not be empty");
            }
        }
    }

    private record Delivery(byte[] bytes, Throwable failure) {
        private static Delivery bytes(byte[] bytes) {
            return new Delivery(bytes, null);
        }

        private static Delivery complete() {
            return new Delivery(null, null);
        }

        private static Delivery failure(Throwable failure) {
            return new Delivery(null, failure);
        }
    }

    private final class EmitterSubscription implements Flow.Subscription {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void request(long requested) {
            if (!cancelled.get()) {
                SseEmitter.this.request(requested);
            }
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                cancelFromSubscriber();
            }
        }
    }

    private enum NoopSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long requested) {
            // The rejected subscriber is immediately notified of its error.
        }

        @Override
        public void cancel() {
            // No upstream resource was acquired.
        }
    }

    /** Builder for a bounded {@link SseEmitter}. */
    public static final class Builder {
        private int maximumQueuedEvents = DEFAULT_MAXIMUM_QUEUED_EVENTS;
        private long maximumQueuedBytes = DEFAULT_MAXIMUM_QUEUED_BYTES;
        private Duration heartbeatInterval;
        private ScheduledExecutorService heartbeatScheduler;

        private Builder() {
        }

        /** Sets the strictly positive count limit for queued, undelivered events. */
        public Builder maximumQueuedEvents(int maximumQueuedEvents) {
            if (maximumQueuedEvents <= 0) {
                throw new IllegalArgumentException("maximumQueuedEvents must be greater than zero: " + maximumQueuedEvents);
            }
            this.maximumQueuedEvents = maximumQueuedEvents;
            return this;
        }

        /** Sets the strictly positive UTF-8 wire-byte limit for queued, undelivered events. */
        public Builder maximumQueuedBytes(long maximumQueuedBytes) {
            if (maximumQueuedBytes <= 0) {
                throw new IllegalArgumentException("maximumQueuedBytes must be greater than zero: " + maximumQueuedBytes);
            }
            this.maximumQueuedBytes = maximumQueuedBytes;
            return this;
        }

        /**
         * Configures automatic comment heartbeats. The scheduler remains caller-owned and is never
         * shut down by the emitter; closing or cancelling the emitter only cancels its own task.
         */
        public Builder heartbeat(Duration interval, ScheduledExecutorService scheduler) {
            var value = Objects.requireNonNull(interval, "interval");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("SSE heartbeat interval must be greater than zero");
            }
            heartbeatInterval = value;
            heartbeatScheduler = Objects.requireNonNull(scheduler, "scheduler");
            return this;
        }

        /** Builds an emitter with the configured finite limits. */
        public SseEmitter build() {
            return new SseEmitter(this);
        }
    }
}
