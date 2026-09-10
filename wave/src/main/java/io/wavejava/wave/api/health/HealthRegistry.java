package io.wavejava.wave.api.health;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Deadline;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle-aware, bounded liveness and readiness evaluator.
 *
 * <p>No health check runs on a transport EventLoop: endpoint handlers run on Wave's invocation
 * runtime, while this registry only waits for bounded {@link java.util.concurrent.CompletionStage}
 * results. It admits a finite number of concurrent evaluations and rejects excess work as DOWN
 * rather than retaining a health-request queue.</p>
 */
public final class HealthRegistry {
    /** Lifecycle state used to separate process liveness from traffic readiness. */
    public enum LifecycleState {
        STARTING,
        READY,
        STOPPING,
        STOPPED,
        FAILED
    }

    private final List<HealthCheck> checks;
    private final HealthLimits limits;
    private final Semaphore evaluations;
    private final AtomicReference<LifecycleState> lifecycle = new AtomicReference<>(LifecycleState.STARTING);

    private HealthRegistry(List<HealthCheck> checks, HealthLimits limits) {
        this.checks = List.copyOf(checks);
        this.limits = limits;
        evaluations = new Semaphore(limits.maximumConcurrentEvaluations(), true);
    }

    /** Starts a builder for a finite health registry. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the current lifecycle readiness state. */
    public LifecycleState lifecycleState() {
        return lifecycle.get();
    }

    /** Marks services and transport startup as in progress. */
    public void markStarting() {
        lifecycle.set(LifecycleState.STARTING);
    }

    /** Marks all required startup work as complete and permits readiness checks. */
    public void markReady() {
        lifecycle.compareAndSet(LifecycleState.STARTING, LifecycleState.READY);
    }

    /** Makes readiness immediately fail before connection shutdown begins. */
    public void markStopping() {
        lifecycle.set(LifecycleState.STOPPING);
    }

    /** Marks all server resources stopped. */
    public void markStopped() {
        lifecycle.set(LifecycleState.STOPPED);
    }

    /** Marks startup as failed after service rollback or a bind failure. */
    public void markFailed() {
        lifecycle.set(LifecycleState.FAILED);
    }

    /** Returns process liveness without invoking external dependency checks. */
    public HealthStatus liveness() {
        return switch (lifecycle.get()) {
            case STARTING, READY -> HealthStatus.up("process is running");
            case STOPPING -> HealthStatus.down("process is stopping");
            case STOPPED -> HealthStatus.down("process is stopped");
            case FAILED -> HealthStatus.down("process startup failed");
        };
    }

    /** Runs bounded readiness checks only after the lifecycle has reached READY. */
    public HealthStatus readiness() {
        if (lifecycle.get() != LifecycleState.READY) {
            return HealthStatus.down("application is not ready")
                    .withDetail("lifecycle", lifecycle.get().name());
        }
        if (!evaluations.tryAcquire()) {
            return HealthStatus.down("health evaluation capacity is exhausted");
        }
        try {
            for (var check : checks) {
                var result = evaluate(check);
                if (!result.isUp()) {
                    return result.withDetail("check", check.name());
                }
            }
            return HealthStatus.up("application is ready");
        } finally {
            evaluations.release();
        }
    }

    /** Returns immutable registered checks in validation order. */
    public List<HealthCheck> checks() {
        return checks;
    }

    /** Returns the finite evaluation policy. */
    public HealthLimits limits() {
        return limits;
    }

    private HealthStatus evaluate(HealthCheck check) {
        var token = CancellationToken.create();
        var timeout = limits.checkTimeout();
        var context = new HealthCheckContext(Deadline.at(Instant.now().plus(timeout)), token);
        final java.util.concurrent.CompletableFuture<HealthStatus> completion;
        try {
            completion = Objects.requireNonNull(check.check(context), "health check stage").toCompletableFuture();
        } catch (RuntimeException failure) {
            return HealthStatus.down("health check failed");
        }
        try {
            var status = completion.get(toNanosSaturated(timeout), TimeUnit.NANOSECONDS);
            return status == null
                    ? HealthStatus.down("health check returned no status")
                    : status;
        } catch (TimeoutException timeoutFailure) {
            token.cancel("health check deadline exceeded");
            completion.cancel(true);
            return HealthStatus.down("health check timed out");
        } catch (InterruptedException interrupted) {
            token.cancel("health check interrupted");
            completion.cancel(true);
            Thread.currentThread().interrupt();
            return HealthStatus.down("health check interrupted");
        } catch (ExecutionException | CompletionException | CancellationException failure) {
            token.cancel("health check failed");
            return HealthStatus.down("health check failed");
        }
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /** Builder for a finite registry with unique low-cardinality check names. */
    public static final class Builder {
        private final List<HealthCheck> checks = new ArrayList<>();
        private HealthLimits limits = HealthLimits.defaults();

        private Builder() {
        }

        /** Adds one required readiness check. */
        public Builder check(HealthCheck check) {
            checks.add(Objects.requireNonNull(check, "check"));
            return this;
        }

        /** Replaces all finite readiness evaluation limits. */
        public Builder limits(HealthLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /** Validates check names/count and creates a lifecycle-starting registry. */
        public HealthRegistry build() {
            if (checks.size() > limits.maximumChecks()) {
                throw new IllegalStateException("health check count exceeds maximumChecks");
            }
            Set<String> names = new HashSet<>();
            for (var check : checks) {
                var name = validateCheckName(check.name());
                if (!names.add(name)) {
                    throw new IllegalStateException("duplicate health check name: " + name);
                }
            }
            return new HealthRegistry(checks, limits);
        }

        private static String validateCheckName(String name) {
            var candidate = Objects.requireNonNull(name, "health check name");
            if (candidate.isBlank() || candidate.length() > 64
                    || !candidate.chars().allMatch(character -> Character.isLetterOrDigit(character)
                            || character == '-' || character == '_' || character == '.')) {
                throw new IllegalArgumentException("health check name must be a bounded low-cardinality token");
            }
            return candidate;
        }
    }
}
