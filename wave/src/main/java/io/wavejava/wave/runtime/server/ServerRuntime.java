package io.wavejava.wave.runtime.server;

import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.lifecycle.ServiceLifecycle;
import io.wavejava.wave.api.observability.Observability;
import io.wavejava.wave.runtime.ApplicationRuntime;
import io.wavejava.wave.runtime.InvocationRuntime;
import io.wavejava.wave.runtime.ObservabilityDispatcher;
import io.wavejava.wave.runtime.RequestDispatcher;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Owns application invocation, services, and observability for one server run. */
public final class ServerRuntime {
    private final ApplicationRuntime application;
    private final InvocationRuntime invocations = new InvocationRuntime();
    private final ServiceLifecycle services;
    private final ObservabilityDispatcher observability;
    private boolean started;

    /** Creates one unstarted server runtime. */
    public ServerRuntime(WaveApp application, Observability observability) {
        this.application = new ApplicationRuntime(Objects.requireNonNull(application, "application"));
        services = this.application.newServiceLifecycle();
        this.observability = new ObservabilityDispatcher(Objects.requireNonNull(observability, "observability"));
    }

    /** Starts application services before a listener may bind. */
    public void start() {
        services.start().toCompletableFuture().join();
        started = true;
    }

    /** Returns the application dispatcher used by a transport. */
    public RequestDispatcher dispatcher() {
        return application;
    }

    /** Returns the virtual-thread invocation owner used by a transport. */
    public InvocationRuntime invocations() {
        return invocations;
    }

    /** Returns the bounded observability dispatcher used by a transport. */
    public ObservabilityDispatcher observability() {
        return observability;
    }

    /** Stops fresh invocation while a transport begins its connection drain. */
    public void beginShutdown() {
        invocations.beginShutdown();
    }

    /** Completes runtime-owned shutdown after the transport has drained its EventLoops. */
    public RuntimeException finishShutdown(Duration timeout) {
        // A transport can fail before it reaches its normal beginShutdown callback.
        // InvocationRuntime requires shutdown to have begun before it can be awaited.
        beginShutdown();
        var budget = new ShutdownBudget(timeout);
        invocations.finishShutdown();
        var interrupted = awaitInvocationTermination(invocations, budget);
        var serviceFailure = stopServices(budget);
        observability.close(Duration.ofNanos(budget.remainingNanos()));
        if (interrupted) Thread.currentThread().interrupt();
        return serviceFailure;
    }

    /** Cleans up resources when listener startup did not complete. */
    public void abortStartup(RuntimeException startupFailure) {
        Objects.requireNonNull(startupFailure, "startupFailure");
        invocations.shutdown();
        observability.close(Duration.ZERO);
        if (!started) return;
        try {
            services.stop().toCompletableFuture().join();
        } catch (RuntimeException stopFailure) {
            startupFailure.addSuppressed(unwrap(stopFailure));
        }
    }

    private static boolean awaitInvocationTermination(InvocationRuntime invocations, ShutdownBudget budget) {
        var interrupted = false;
        while (!invocations.isTerminated()) {
            var remaining = budget.remainingNanos();
            if (remaining == 0) return interrupted;
            try {
                invocations.awaitTermination(Duration.ofNanos(remaining));
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private RuntimeException stopServices(ShutdownBudget budget) {
        final java.util.concurrent.CompletableFuture<Void> completion;
        try {
            completion = services.stop().toCompletableFuture();
        } catch (RuntimeException failure) {
            return failure;
        }
        var interrupted = false;
        while (!completion.isDone()) {
            var remaining = budget.remainingNanos();
            if (remaining == 0) return new IllegalStateException("Timed out stopping application services");
            try {
                completion.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            } catch (TimeoutException ignored) {
                return new IllegalStateException("Timed out stopping application services");
            } catch (ExecutionException failure) {
                return unwrap(failure.getCause());
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        try {
            completion.join();
            return null;
        } catch (java.util.concurrent.CompletionException failure) {
            return unwrap(failure.getCause());
        }
    }

    private static RuntimeException unwrap(Throwable failure) {
        return failure instanceof RuntimeException runtimeFailure
                ? runtimeFailure
                : new IllegalStateException("Application service shutdown failed", failure);
    }

    private static final class ShutdownBudget {
        private final long timeoutNanos;
        private final long startedAt = System.nanoTime();

        private ShutdownBudget(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            timeoutNanos = toNanosSaturated(timeout);
        }

        private static long toNanosSaturated(Duration timeout) {
            try {
                return timeout.toNanos();
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }

        private long remainingNanos() {
            var elapsed = System.nanoTime() - startedAt;
            return elapsed <= 0 ? timeoutNanos : elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
        }
    }
}
