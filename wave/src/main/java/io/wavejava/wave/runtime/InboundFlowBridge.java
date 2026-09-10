package io.wavejava.wave.runtime;

import io.wavejava.wave.api.stream.BodyPublisher;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

/**
 * A bounded, transport-fed {@link BodyPublisher} for one streaming HTTP request body.
 *
 * <p>The Netty adapter feeds a copied HTTP content chunk through {@link #offer(byte[], boolean)}.
 * This bridge retains at most one chunk and dispatches subscriber callbacks on the supplied
 * application executor, never on the transport event loop. It asks the adapter to read only when
 * the subscriber has demand and no item is retained or executing.</p>
 *
 * <p>Subscription hand-off is deliberately two phase: the subscriber is installed while holding
 * the state lock, but {@link Flow.Subscriber#onSubscribe(Flow.Subscription)} is invoked after the
 * lock is released. No {@code onNext}, {@code onError}, or {@code onComplete} callback may run
 * until that method returns. This keeps a slow application subscriber from blocking Netty while
 * preserving the Reactive Streams callback ordering rule.</p>
 */
public final class InboundFlowBridge implements BodyPublisher {
    /** Receives transport-safe demand and terminal notifications. */
    public interface Listener {
        /** A subscriber has demand and the adapter may issue one manual transport read. */
        void onDemandAvailable();

        /** The publisher delivered its final completion callback. */
        void onComplete();

        /** A bound or subscriber failure requires transport cancellation. */
        void onFailure(String reason, Throwable cause);

        /** The bridge released all locally retained state without a transport failure. */
        void onCancelled();
    }

    /** State visible to deterministic contract tests. */
    public record Snapshot(
            long maximumBytes,
            long receivedBytes,
            long outstandingDemand,
            boolean subscribed,
            boolean pendingChunk,
            boolean deliveryInFlight,
            boolean readRequested,
            boolean sourceCompleted,
            boolean cancelled) {
        public Snapshot {
            if (maximumBytes <= 0 || receivedBytes < 0 || receivedBytes > maximumBytes || outstandingDemand < 0) {
                throw new IllegalArgumentException("invalid inbound Flow bridge snapshot");
            }
        }
    }

    private final Object lock = new Object();
    private final Executor callbackExecutor;
    private final long maximumBytes;
    private final Listener listener;

    private Flow.Subscriber<? super ByteBuffer> subscriber;
    private long receivedBytes;
    private long outstandingDemand;
    private byte[] pendingChunk;
    private boolean deliveryInFlight;
    private boolean callbackInProgress;
    private boolean readRequested;
    private boolean sourceCompleted;
    private boolean completionScheduled;
    private boolean subscriptionReady;
    private boolean cancelled;
    private boolean terminalSignalClaimed;
    private boolean terminalCallbackDelivered;
    private boolean terminalErrorScheduled;
    private long callbackGeneration;
    private TerminalFailure terminalFailure;

    /** Creates a bridge with an explicit aggregate-equivalent total inbound byte limit. */
    public InboundFlowBridge(Executor callbackExecutor, long maximumBytes, Listener listener) {
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be greater than zero: " + maximumBytes);
        }
        this.maximumBytes = maximumBytes;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Feeds one transport-owned chunk after it has been copied to framework-owned storage.
     *
     * @return {@code true} when the chunk was accepted; {@code false} after cancellation
     */
    public boolean offer(byte[] bytes, boolean last) {
        var owned = Objects.requireNonNull(bytes, "bytes");
        Drain drain;
        Failure failure = null;
        synchronized (lock) {
            if (cancelled) {
                return false;
            }
            // This content satisfies the one manually issued transport read, if any.
            readRequested = false;
            if (sourceCompleted) {
                failure = failLocked("received request content after the terminal chunk", null);
                drain = terminalDrainLocked();
            } else if (owned.length > maximumBytes - receivedBytes) {
                failure = failLocked("streaming request body exceeded its byte budget", null);
                drain = terminalDrainLocked();
            } else if (owned.length > 0 && (pendingChunk != null || deliveryInFlight)) {
                failure = failLocked("streaming request body exceeded its one-chunk transport buffer", null);
                drain = terminalDrainLocked();
            } else {
                receivedBytes += owned.length;
                if (owned.length > 0) {
                    pendingChunk = owned;
                }
                if (last) {
                    sourceCompleted = true;
                }
                drain = drainLocked();
            }
        }
        notifyFailure(failure);
        runDrain(drain);
        return failure == null;
    }

    /** Cancels local state and notifies a subscriber with a cooperative cancellation failure. */
    public void cancel(String reason) {
        Objects.requireNonNull(reason, "reason");
        Drain drain;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pendingChunk = null;
            outstandingDemand = 0;
            readRequested = false;
            completionScheduled = false;
            callbackGeneration++;
            terminalFailure = new TerminalFailure(reason, new CancellationException(reason));
            drain = terminalDrainLocked();
        }
        runDrain(drain);
        listener.onCancelled();
    }

    /** Returns whether HTTP input reached its terminal content chunk. */
    public boolean isSourceCompleted() {
        synchronized (lock) {
            return sourceCompleted;
        }
    }

    /**
     * Returns whether a transport may safely release its request lifecycle after the source ends.
     *
     * <p>An unclaimed empty body is releasable because no bytes or application callback remain.
     * A subscribed publisher becomes releasable only after its terminal callback has returned;
     * this prevents a keep-alive connection from outliving a blocked final {@code onNext} or
     * {@code onComplete} callback.</p>
     */
    public boolean isTransportReleasable() {
        synchronized (lock) {
            return sourceCompleted
                    && pendingChunk == null
                    && !deliveryInFlight
                    && !callbackInProgress
                    && (subscriber == null || terminalCallbackDelivered);
        }
    }

    /** Returns a deterministic state snapshot. */
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    maximumBytes,
                    receivedBytes,
                    outstandingDemand,
                    subscriber != null,
                    pendingChunk != null,
                    deliveryInFlight,
                    readRequested,
                    sourceCompleted,
                    cancelled);
        }
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> candidate) {
        var target = Objects.requireNonNull(candidate, "subscriber");
        var rejected = false;
        synchronized (lock) {
            if (subscriber != null) {
                rejected = true;
            } else {
                subscriber = target;
            }
        }
        if (rejected) {
            rejectSecondSubscriber(target);
            return;
        }

        try {
            // Do not hold lock while arbitrary application code runs. A request made from
            // onSubscribe is retained but cannot schedule a callback until this call returns.
            target.onSubscribe(new Subscription());
        } catch (Throwable callbackFailure) {
            Failure failure;
            synchronized (lock) {
                failure = failLocked("request body subscriber rejected onSubscribe", callbackFailure);
            }
            notifyFailure(failure);
            return;
        }

        Drain drain;
        synchronized (lock) {
            subscriptionReady = true;
            drain = cancelled ? terminalDrainLocked() : drainLocked();
        }
        runDrain(drain);
    }

    private void rejectSecondSubscriber(Flow.Subscriber<? super ByteBuffer> target) {
        // Reactive Streams requires onSubscribe before terminal notification. This is deliberately
        // outside the bridge lock for the same reason as the primary subscriber callback.
        target.onSubscribe(RejectedSubscription.INSTANCE);
        target.onError(new IllegalStateException("Request body publisher may be subscribed only once"));
    }

    private void request(long demand) {
        Failure failure = null;
        Drain drain;
        synchronized (lock) {
            if (cancelled || completionScheduled || terminalSignalClaimed) {
                return;
            }
            if (demand <= 0) {
                failure = failLocked("request body subscriber requested non-positive demand", null);
                drain = terminalDrainLocked();
            } else {
                outstandingDemand = saturatingAdd(outstandingDemand, demand);
                drain = drainLocked();
            }
        }
        notifyFailure(failure);
        runDrain(drain);
    }

    private void cancelFromSubscriber() {
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pendingChunk = null;
            outstandingDemand = 0;
            readRequested = false;
            completionScheduled = false;
            callbackGeneration++;
        }
        listener.onCancelled();
    }

    private Drain drainLocked() {
        if (cancelled || subscriber == null || !subscriptionReady || deliveryInFlight || callbackInProgress
                || terminalSignalClaimed) {
            return Drain.none();
        }
        if (pendingChunk != null && outstandingDemand > 0) {
            var bytes = pendingChunk;
            pendingChunk = null;
            outstandingDemand--;
            deliveryInFlight = true;
            return Drain.delivery(subscriber, ByteBuffer.wrap(bytes).asReadOnlyBuffer(), callbackGeneration);
        }
        if (sourceCompleted && pendingChunk == null && !completionScheduled) {
            completionScheduled = true;
            return Drain.completion(subscriber, callbackGeneration);
        }
        if (!sourceCompleted && pendingChunk == null && outstandingDemand > 0 && !readRequested) {
            readRequested = true;
            return Drain.readDemandSignal();
        }
        return Drain.none();
    }

    /** Creates an error callback only after onSubscribe and any active callback finish. */
    private Drain terminalDrainLocked() {
        if (terminalFailure == null || subscriber == null || !subscriptionReady || callbackInProgress
                || terminalSignalClaimed || terminalErrorScheduled) {
            return Drain.none();
        }
        terminalErrorScheduled = true;
        return Drain.error(subscriber, terminalFailure.cause(), callbackGeneration);
    }

    private void runDrain(Drain drain) {
        if (drain.kind() == DrainKind.READ_DEMAND) {
            listener.onDemandAvailable();
            return;
        }
        if (drain.kind() == DrainKind.NONE) {
            return;
        }
        try {
            callbackExecutor.execute(() -> {
                switch (drain.kind()) {
                    case DELIVERY -> deliver(drain);
                    case COMPLETION -> complete(drain);
                    case ERROR -> signalError(drain);
                    case NONE, READ_DEMAND -> throw new AssertionError("invalid callback drain");
                }
            });
        } catch (RuntimeException rejection) {
            if (drain.kind() == DrainKind.ERROR) {
                // The transport listener was already told about this terminal failure. There is no
                // safe fallback executor that could preserve the off-event-loop callback contract.
                return;
            }
            Failure failure;
            synchronized (lock) {
                failure = failLocked("request body callback executor rejected delivery", rejection);
            }
            notifyFailure(failure);
        }
    }

    private void deliver(Drain drain) {
        if (!beginDelivery(drain)) {
            return;
        }

        Failure failure = null;
        Drain next = Drain.none();
        try {
            drain.subscriber().onNext(Objects.requireNonNull(drain.buffer(), "buffer"));
        } catch (Throwable callbackFailure) {
            synchronized (lock) {
                callbackInProgress = false;
                if (!cancelled && callbackGeneration == drain.generation()) {
                    deliveryInFlight = false;
                    failure = failLocked("request body subscriber failed", callbackFailure);
                }
                next = terminalDrainLocked();
            }
            notifyFailure(failure);
            runDrain(next);
            return;
        }

        synchronized (lock) {
            callbackInProgress = false;
            if (!cancelled && callbackGeneration == drain.generation()) {
                deliveryInFlight = false;
                next = drainLocked();
            } else {
                next = terminalDrainLocked();
            }
        }
        runDrain(next);
    }

    private boolean beginDelivery(Drain drain) {
        synchronized (lock) {
            if (cancelled || !subscriptionReady || subscriber != drain.subscriber()
                    || callbackGeneration != drain.generation() || !deliveryInFlight || callbackInProgress) {
                return false;
            }
            callbackInProgress = true;
            return true;
        }
    }

    private void complete(Drain drain) {
        if (!beginCompletion(drain)) {
            return;
        }
        try {
            drain.subscriber().onComplete();
        } catch (Throwable callbackFailure) {
            listener.onFailure("request body subscriber failed during completion", callbackFailure);
            return;
        } finally {
            synchronized (lock) {
                callbackInProgress = false;
                terminalCallbackDelivered = true;
            }
        }
        listener.onComplete();
    }

    private boolean beginCompletion(Drain drain) {
        synchronized (lock) {
            if (cancelled || !subscriptionReady || subscriber != drain.subscriber()
                    || callbackGeneration != drain.generation() || !completionScheduled || callbackInProgress
                    || terminalSignalClaimed) {
                return false;
            }
            callbackInProgress = true;
            terminalSignalClaimed = true;
            return true;
        }
    }

    private void signalError(Drain drain) {
        if (!beginError(drain)) {
            return;
        }
        try {
            drain.subscriber().onError(Objects.requireNonNull(drain.failure(), "failure"));
        } catch (Throwable ignored) {
            // The transport has already been told that this stream is terminal. An application
            // exception from onError cannot be made observable without violating Flow ordering.
        } finally {
            synchronized (lock) {
                callbackInProgress = false;
                terminalCallbackDelivered = true;
            }
        }
    }

    private boolean beginError(Drain drain) {
        synchronized (lock) {
            if (!cancelled || !subscriptionReady || subscriber != drain.subscriber()
                    || callbackGeneration != drain.generation() || !terminalErrorScheduled || callbackInProgress
                    || terminalSignalClaimed || terminalFailure == null || terminalFailure.cause() != drain.failure()) {
                return false;
            }
            callbackInProgress = true;
            terminalSignalClaimed = true;
            return true;
        }
    }

    private Failure failLocked(String reason, Throwable cause) {
        if (cancelled) {
            return null;
        }
        cancelled = true;
        pendingChunk = null;
        outstandingDemand = 0;
        readRequested = false;
        completionScheduled = false;
        callbackGeneration++;
        var failure = cause == null ? new IllegalStateException(reason) : cause;
        terminalFailure = new TerminalFailure(reason, failure);
        return new Failure(reason, failure);
    }

    private void notifyFailure(Failure failure) {
        if (failure != null) {
            listener.onFailure(failure.reason(), failure.cause());
        }
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private enum DrainKind {
        NONE,
        READ_DEMAND,
        DELIVERY,
        COMPLETION,
        ERROR
    }

    private record Drain(
            DrainKind kind,
            Flow.Subscriber<? super ByteBuffer> subscriber,
            ByteBuffer buffer,
            Throwable failure,
            long generation) {
        static Drain none() {
            return new Drain(DrainKind.NONE, null, null, null, 0);
        }

        static Drain readDemandSignal() {
            return new Drain(DrainKind.READ_DEMAND, null, null, null, 0);
        }

        static Drain delivery(Flow.Subscriber<? super ByteBuffer> subscriber, ByteBuffer buffer, long generation) {
            return new Drain(DrainKind.DELIVERY, subscriber, buffer, null, generation);
        }

        static Drain completion(Flow.Subscriber<? super ByteBuffer> subscriber, long generation) {
            return new Drain(DrainKind.COMPLETION, subscriber, null, null, generation);
        }

        static Drain error(Flow.Subscriber<? super ByteBuffer> subscriber, Throwable failure, long generation) {
            return new Drain(DrainKind.ERROR, subscriber, null, failure, generation);
        }
    }

    private record Failure(String reason, Throwable cause) {
    }

    private record TerminalFailure(String reason, Throwable cause) {
    }

    private final class Subscription implements Flow.Subscription {
        @Override
        public void request(long demand) {
            InboundFlowBridge.this.request(demand);
        }

        @Override
        public void cancel() {
            cancelFromSubscriber();
        }
    }

    private enum RejectedSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long demand) {
            // The rejected subscriber receives onError immediately.
        }

        @Override
        public void cancel() {
            // Nothing was subscribed upstream.
        }
    }
}
