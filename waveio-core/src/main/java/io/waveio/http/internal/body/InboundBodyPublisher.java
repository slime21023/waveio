package io.waveio.http.internal.body;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

public final class InboundBodyPublisher implements Flow.Publisher<ByteBuffer> {
    private static final System.Logger LOG =
            System.getLogger(InboundBodyPublisher.class.getName());

    private final long maximumBytes;
    private final Consumer<Runnable> executor;
    private final Runnable requestRead;
    private final Runnable cancelTransport;
    private final ArrayDeque<ByteBuffer> queued = new ArrayDeque<>();
    private Flow.Subscriber<? super ByteBuffer> subscriber;
    private long demand;
    private long received;
    private boolean readOutstanding;
    private boolean sourceCompleted;
    private boolean terminal;
    private boolean cancelled;
    private boolean terminalSignalled;
    private boolean draining;
    private boolean drainRequested;
    private Throwable failure;

    public InboundBodyPublisher(long maximumBytes, Consumer<Runnable> executor,
            Runnable requestRead, Runnable cancelTransport) {
        if (maximumBytes < 0) throw new IllegalArgumentException("maximumBytes must not be negative");
        this.maximumBytes = maximumBytes;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.requestRead = Objects.requireNonNull(requestRead, "requestRead");
        this.cancelTransport = Objects.requireNonNull(cancelTransport, "cancelTransport");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> value) {
        Objects.requireNonNull(value, "subscriber");
        executor.accept(() -> subscribeOnExecutor(value));
    }

    /**
     * Accepts one decoded body chunk. A single transport read can decode several chunks before any
     * of them is signalled, so undemanded data is buffered in arrival order rather than rejected;
     * {@code maxBodySize} remains the only bound on retained bytes.
     */
    public boolean offer(ByteBuffer value) {
        Objects.requireNonNull(value, "value");
        if (terminal || cancelled || sourceCompleted) return false;
        readOutstanding = false;
        int size = value.remaining();
        if (size > maximumBytes - received) {
            fail(new BodyTooLargeException(maximumBytes));
            return false;
        }
        received += size;
        var detached = detach(value, size);
        if (subscriber != null && demand > 0 && queued.isEmpty()) {
            demand--;
            if (emit(detached)) requestTransportIfNeeded();
        } else {
            queued.addLast(detached);
        }
        return true;
    }

    public void complete() {
        if (terminal || sourceCompleted) return;
        sourceCompleted = true;
        drain();
    }

    public void disconnect() {
        fail(new DisconnectedException());
    }

    public void fail(Throwable value) {
        if (terminal || cancelled) return;
        failure = Objects.requireNonNull(value, "failure");
        terminal = true;
        queued.clear();
        if (subscriber != null) signalTerminal(() -> subscriber.onError(failure));
    }

    public boolean isTerminal() {
        return terminal || cancelled;
    }

    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    private void subscribeOnExecutor(Flow.Subscriber<? super ByteBuffer> value) {
        if (subscriber != null) {
            signalRejected(value);
            return;
        }
        subscriber = value;
        // Demand renewed from inside onSubscribe re-enters request/drain before this returns, so
        // the stream can already be terminal — and already signalled — at this point.
        if (!signal(() -> value.onSubscribe(new BodySubscription()))) return;
        if (terminalSignalled) return;
        if (failure != null) signalTerminal(() -> value.onError(failure));
        else if (terminal) signalTerminal(value::onComplete);
        else drain();
    }

    private void request(long amount) {
        if (cancelled || terminal) return;
        if (amount <= 0) {
            fail(new IllegalArgumentException("Demand must be positive"));
            cancelTransport.run();
            return;
        }
        demand = addCap(demand, amount);
        drain();
    }

    /**
     * Replays buffered chunks iteratively. Subscribers commonly renew demand from inside
     * {@code onNext}, which re-enters this method on the same thread; without the trampoline a
     * replayed read batch would cost one stack frame group per chunk.
     */
    private void drain() {
        if (draining) {
            drainRequested = true;
            return;
        }
        draining = true;
        try {
            do {
                drainRequested = false;
                drainOnce();
            } while (drainRequested && !terminal && !cancelled);
        } finally {
            draining = false;
        }
    }

    private void drainOnce() {
        if (subscriber == null || terminal || cancelled) return;
        while (!queued.isEmpty() && demand > 0) {
            demand--;
            if (!emit(queued.removeFirst())) return;
        }
        if (sourceCompleted && queued.isEmpty()) {
            terminal = true;
            signalTerminal(subscriber::onComplete);
            return;
        }
        requestTransportIfNeeded();
    }

    private boolean emit(ByteBuffer value) {
        return signal(() -> subscriber.onNext(value));
    }

    private void signalTerminal(Runnable callback) {
        if (terminalSignalled) return;
        terminalSignalled = true;
        signal(callback);
    }

    private void requestTransportIfNeeded() {
        if (!terminal && !cancelled && !sourceCompleted && demand > 0 && queued.isEmpty()
                && !readOutstanding) {
            readOutstanding = true;
            requestRead.run();
        }
    }

    private static ByteBuffer detach(ByteBuffer value, int size) {
        var bytes = new byte[size];
        value.duplicate().get(bytes);
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    private void cancel() {
        if (cancelled || terminal) return;
        cancelled = true;
        queued.clear();
        cancelTransport.run();
    }

    private boolean signal(Runnable callback) {
        try {
            callback.run();
            return true;
        } catch (Throwable subscriberFailure) {
            LOG.log(System.Logger.Level.TRACE,
                    "Request body subscriber callback failed; cancelling the stream",
                    subscriberFailure);
            cancelled = true;
            terminal = true;
            queued.clear();
            try {
                cancelTransport.run();
            } catch (Throwable cancellationFailure) {
                LOG.log(System.Logger.Level.TRACE,
                        "Transport cancellation hook failed", cancellationFailure);
                // A broken transport cancellation hook must not rethrow user callback failure.
            }
            return false;
        }
    }

    private static void signalRejected(Flow.Subscriber<? super ByteBuffer> subscriber) {
        try {
            subscriber.onSubscribe(RejectedSubscription.INSTANCE);
            subscriber.onError(new IllegalStateException(
                    "Request body supports only one subscriber"));
        } catch (Throwable rejectionFailure) {
            LOG.log(System.Logger.Level.TRACE,
                    "Rejected body subscriber threw from its own terminal signal",
                    rejectionFailure);
            // A rejected subscriber has no ownership of the active body stream.
        }
    }

    private static long addCap(long left, long right) {
        long result = left + right;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    public static final class BodyTooLargeException extends RuntimeException {
        public BodyTooLargeException(long maximumBytes) {
            super("Request body exceeds " + maximumBytes + " bytes");
        }
    }

    public static final class DisconnectedException extends RuntimeException {
        public DisconnectedException() { super("Connection closed while reading request body"); }
    }

    public static final class BodyNotConsumedException extends RuntimeException {
        public BodyNotConsumedException() {
            super("Streaming handler completed before consuming the request body");
        }
    }

    private final class BodySubscription implements Flow.Subscription {
        @Override public void request(long amount) { executor.accept(() -> InboundBodyPublisher.this.request(amount)); }
        @Override public void cancel() { executor.accept(InboundBodyPublisher.this::cancel); }
    }

    private enum RejectedSubscription implements Flow.Subscription {
        INSTANCE;
        @Override public void request(long amount) {}
        @Override public void cancel() {}
    }
}
