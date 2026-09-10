package io.wavejava.wave.runtime;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One-way lifecycle coordinator for a running server.
 *
 * <p>The coordinator deliberately owns no Netty type. A transport uses {@link #beginShutdown()}
 * as its single admission point before it closes the listener, cancels request execution, and
 * releases connections. This prevents two concurrent {@code RunningServer.close()} calls from
 * performing interleaved teardown.</p>
 */
public final class ShutdownCoordinator {
    /** Observable internal lifecycle phases. */
    public enum State {
        RUNNING,
        SHUTTING_DOWN,
        TERMINATED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    /** Begins shutdown exactly once. */
    public boolean beginShutdown() {
        return state.compareAndSet(State.RUNNING, State.SHUTTING_DOWN);
    }

    /** Marks all runtime and transport resources as released. */
    public void completeShutdown() {
        state.compareAndSet(State.SHUTTING_DOWN, State.TERMINATED);
    }

    /** Returns the current lifecycle phase. */
    public State state() {
        return state.get();
    }

    /** Returns whether this server can still accept new work. */
    public boolean isRunning() {
        return state() == State.RUNNING;
    }
}
