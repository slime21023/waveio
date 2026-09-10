package io.wavejava.wave.runtime;

import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.RequestContext;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Internal bridge from a protocol adapter to virtual-thread application invocation.
 *
 * <p>The runtime owns a virtual-thread-per-task executor and a small scheduler used only to turn
 * request deadlines into cooperative cancellation. Netty adapters submit a request with transport
 * created {@link RequestContext}, observe the returned {@link RequestInvocation}, cancel it on
 * disconnect, and shut this runtime down when the server stops. No Netty type crosses this API.</p>
 */
public final class InvocationRuntime implements AutoCloseable {
    private static final String SHUTDOWN_REASON = "server shutdown";

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Set<RequestInvocation<?>> activeInvocations = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();

    private boolean shutdown;

    /** Creates a runtime backed by virtual threads and a daemon deadline scheduler. */
    public InvocationRuntime() {
        this(
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("wave-deadline-", 0).factory()),
                Clock.systemUTC());
    }

    InvocationRuntime(ExecutorService executor, ScheduledExecutorService scheduler, Clock clock) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Submits one request with its already-created execution context.
     *
     * <p>The supplied request is copied only to attach {@code context}; its bounded body remains
     * shared with the original request, so an adapter must submit it at most once.</p>
     *
     * @param request framework-owned request decoded by the transport
     * @param context context containing the request ID, optional deadline, and cancellation token
     * @param task application operation to execute outside the transport event loop
     * @throws RejectedExecutionException when shutdown has begun or the executor rejects work
     */
    public <T> RequestInvocation<T> submit(Request request, RequestContext context, RequestTask<T> task) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(task, "task");

        var invocation = new RequestInvocation<>(
                this,
                request.withContext(context),
                context,
                task,
                scheduler,
                clock);
        synchronized (lifecycleLock) {
            if (shutdown) {
                throw new RejectedExecutionException("Invocation runtime is shutting down");
            }
            activeInvocations.add(invocation);
            try {
                invocation.start(executor);
            } catch (RuntimeException failure) {
                activeInvocations.remove(invocation);
                throw failure;
            }
        }
        return invocation;
    }

    /**
     * Submits a request that already carries its execution context.
     *
     * @throws IllegalArgumentException when the request has no context
     */
    public <T> RequestInvocation<T> submit(Request request, RequestTask<T> task) {
        Objects.requireNonNull(request, "request");
        var context = request.context().orElseThrow(() -> new IllegalArgumentException(
                "request must carry a RequestContext; use submit(request, context, task)"));
        return submit(request, context, task);
    }

    /**
     * Schedules a transport-owned callback on the same virtual-thread executor as handlers.
     *
     * <p>This keeps Flow request-body callbacks out of a Netty event loop without making the
     * transport responsible for an additional unbounded executor or queue.</p>
     *
     * @throws RejectedExecutionException when shutdown has begun or the executor rejects work
     */
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (lifecycleLock) {
            if (shutdown) {
                throw new RejectedExecutionException("Invocation runtime is shutting down");
            }
            executor.execute(task);
        }
    }

    /**
     * Schedules a narrowly scoped transport-cleanup callback while server shutdown is closing
     * child channels.
     *
     * <p>This is intentionally separate from {@link #execute(Runnable)}: shutdown must reject
     * fresh application work, yet an already active Flow publisher still has to receive its
     * off-event-loop {@code cancel()} callback before the virtual-thread executor is finally
     * stopped. Adapters may use this only for terminal Flow delivery and publisher teardown, not
     * for new request invocation or response production.</p>
     *
     * @throws RejectedExecutionException once final executor shutdown has started
     */
    public void executeTransportCleanup(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (lifecycleLock) {
            executor.execute(task);
        }
    }

    /**
     * Cancels an invocation owned by this runtime.
     *
     * @return {@code true} only if cancellation won the invocation's terminal-state race
     */
    public boolean cancel(RequestInvocation<?> invocation, String reason) {
        Objects.requireNonNull(invocation, "invocation");
        if (!invocation.belongsTo(this)) {
            throw new IllegalArgumentException("Invocation belongs to a different runtime");
        }
        return invocation.cancel(reason);
    }

    /** Returns whether this runtime has stopped accepting new invocations. */
    public boolean isShutdown() {
        synchronized (lifecycleLock) {
            return shutdown;
        }
    }

    /** Returns whether both owned executors have terminated. */
    public boolean isTerminated() {
        return executor.isTerminated() && scheduler.isTerminated();
    }

    /**
     * Stops accepting fresh work and cooperatively cancels active invocations, while leaving the
     * virtual-thread executor available for already-active transport cleanup.
     *
     * <p>A server lifecycle calls this before it closes child connections, so response publishers
     * can be cancelled off their Netty event loops. {@link #finishShutdown()} then closes the
     * executor. Standalone owners should normally call {@link #shutdown()} instead.</p>
     */
    public void beginShutdown() {
        var toCancel = new ArrayList<RequestInvocation<?>>();
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            toCancel.addAll(activeInvocations);
        }

        for (var invocation : toCancel) {
            invocation.cancel(SHUTDOWN_REASON);
        }
        scheduler.shutdownNow();
    }

    /** Starts final executor shutdown after transport cleanup has been admitted. */
    public void finishShutdown() {
        executor.shutdown();
    }

    /**
     * Stops accepting work, cooperatively cancels active invocations, and starts executor shutdown.
     *
     * <p>The method does not wait for application cleanup. Call {@link #awaitTermination(Duration)}
     * when a lifecycle owner needs a bounded wait.</p>
     */
    public void shutdown() {
        beginShutdown();
        finishShutdown();
    }

    /**
     * Waits for the owned scheduler and virtual-thread executor after shutdown has started.
     *
     * @return {@code true} when both executors terminate before {@code timeout}
     * @throws IllegalStateException when shutdown has not started
     */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (!isShutdown()) {
            throw new IllegalStateException("Invocation runtime has not started shutdown");
        }

        var totalNanos = toNanosSaturated(timeout);
        var startedAt = System.nanoTime();
        if (!executor.awaitTermination(totalNanos, TimeUnit.NANOSECONDS)) {
            return false;
        }
        var elapsed = System.nanoTime() - startedAt;
        var remaining = elapsed >= totalNanos ? 0 : totalNanos - elapsed;
        return scheduler.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    /** Equivalent to {@link #shutdown()}. */
    @Override
    public void close() {
        shutdown();
    }

    void onTerminal(RequestInvocation<?> invocation) {
        activeInvocations.remove(invocation);
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /** Application operation performed for one request invocation. */
    @FunctionalInterface
    public interface RequestTask<T> {
        /** Executes application work against the invocation-owned request. */
        T execute(Request request) throws Exception;
    }
}
