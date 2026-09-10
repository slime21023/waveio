package io.wavejava.wave.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;

/** Test-only owner for a deterministic runtime clock, deadline scheduler, and virtual executor. */
final class ExecutionHarness implements AutoCloseable {
    private final DeterministicClock clock;
    private final DeterministicScheduler scheduler;
    private final InvocationRuntime runtime;

    private ExecutionHarness(Instant now) {
        clock = DeterministicClock.at(Objects.requireNonNull(now, "now"));
        scheduler = new DeterministicScheduler(clock);
        runtime = new InvocationRuntime(Executors.newVirtualThreadPerTaskExecutor(), scheduler, clock);
    }

    static ExecutionHarness at(Instant now) {
        return new ExecutionHarness(now);
    }

    DeterministicClock clock() {
        return clock;
    }

    DeterministicScheduler scheduler() {
        return scheduler;
    }

    InvocationRuntime runtime() {
        return runtime;
    }

    /** Advances test time and delivers every deadline that is now due. */
    void advanceBy(Duration duration) {
        clock.advance(duration);
        scheduler.runDueTasks();
    }

    /** Delivers tasks already due at the current test instant. */
    void runDueTasks() {
        scheduler.runDueTasks();
    }

    @Override
    public void close() {
        runtime.close();
    }
}
