package io.wavejava.wave.netty;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Internal bridge from wave deadlines/tokens to cancellable asynchronous client work. */
public final class CancellationBridge implements AutoCloseable {
    /** Internal cancellation operation; it intentionally is not a {@link Future}. */
    public abstract static class CancellationHandle {
        abstract boolean isDone();

        abstract void requestAbort();
    }

    public record Signal(String reason, boolean timedOut) {
        public Signal {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private final ScheduledExecutorService scheduler;
    private final CancellationToken token;
    private final Consumer<Signal> listener;
    private final Set<CancellationHandle> cancellables = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Signal> signal = new AtomicReference<>();
    private volatile ScheduledFuture<?> deadlineTask;
    private volatile CancellationToken.Registration cancellationRegistration;

    public CancellationBridge(
            ScheduledExecutorService scheduler,
            CancellationToken token,
            Consumer<Signal> listener) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.token = token;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void arm(Deadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (token != null) {
            cancellationRegistration = token.onCancellation(this::cancel);
            if (isSignalled()) {
                return;
            }
        }
        var remainingNanos = toNanosSaturated(deadline.remaining());
        if (remainingNanos == 0) {
            timeout();
            return;
        }
        deadlineTask = scheduler.schedule(this::timeout, remainingNanos, TimeUnit.NANOSECONDS);
        if (isSignalled()) {
            deadlineTask.cancel(false);
        }
    }

    public void register(Future<?> future) {
        Objects.requireNonNull(future, "future");
        register(new FutureCancellationHandle(future));
    }

    public void register(CancellationHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handle.isDone()) {
            return;
        }
        var existing = signal.get();
        if (existing != null) {
            handle.requestAbort();
            return;
        }
        cancellables.add(handle);
        existing = signal.get();
        if (existing != null && cancellables.remove(handle)) {
            handle.requestAbort();
            return;
        }
        if (handle.isDone()) {
            cancellables.remove(handle);
        }
    }

    public void unregister(Future<?> future) {
        if (future != null) {
            unregister(new FutureCancellationHandle(future));
        }
    }

    public void unregister(CancellationHandle handle) {
        if (handle != null) {
            cancellables.remove(handle);
        }
    }

    public boolean isSignalled() {
        return signal.get() != null;
    }

    public Signal signal() {
        return signal.get();
    }

    public void cancel(String reason) {
        signal(new Signal(reason, false));
    }

    public void timeout() {
        signal(new Signal("client deadline exceeded", true));
    }

    private void signal(Signal next) {
        if (!signal.compareAndSet(null, next)) {
            return;
        }
        var registration = cancellationRegistration;
        if (registration != null) {
            registration.close();
        }
        var deadline = deadlineTask;
        if (deadline != null) {
            deadline.cancel(false);
        }
        if (token != null) {
            token.cancel(next.reason());
        }
        var snapshot = new ArrayList<>(cancellables);
        for (var cancellable : snapshot) {
            cancellable.requestAbort();
        }
        listener.accept(next);
    }

    @Override
    public void close() {
        var deadline = deadlineTask;
        if (deadline != null) {
            deadline.cancel(false);
        }
        var registration = cancellationRegistration;
        if (registration != null) {
            registration.close();
        }
        cancellables.clear();
    }

    private static long toNanosSaturated(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return 0;
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static final class FutureCancellationHandle extends CancellationHandle {
        private final Future<?> future;

        private FutureCancellationHandle(Future<?> future) {
            this.future = Objects.requireNonNull(future, "future");
        }

        @Override
        boolean isDone() {
            return future.isDone();
        }

        @Override
        void requestAbort() {
            future.cancel(true);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FutureCancellationHandle that && future == that.future;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(future);
        }
    }
}

