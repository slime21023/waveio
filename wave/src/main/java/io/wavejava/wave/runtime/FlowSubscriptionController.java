package io.wavejava.wave.runtime;

import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Thread-safe, one-item-at-a-time Flow demand state machine for a transport bridge.
 *
 * <p>The controller deliberately has no Netty dependency. An adapter asks it for a subscription
 * to request only when its channel is writable and an outbound-byte budget has room. The adapter
 * invokes {@link Flow.Subscription#request(long)} outside this object's monitor, then reports the
 * resulting item and write completion back here. At most one item may be requested or retained at
 * any time.</p>
 */
public final class FlowSubscriptionController {
    /** Observable lifecycle state. */
    public enum State {
        NEW,
        SUBSCRIBED,
        ACTIVE,
        UPSTREAM_COMPLETED,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    /** Immutable snapshot for deterministic Flow contract tests. */
    public record Snapshot(State state, boolean demandOutstanding, boolean itemOutstanding) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            if ((state == State.NEW || state == State.COMPLETED || state == State.CANCELLED || state == State.FAILED)
                    && (demandOutstanding || itemOutstanding)) {
                throw new IllegalArgumentException("terminal/new state cannot retain demand or an item");
            }
        }
    }

    private State state = State.NEW;
    private Flow.Subscription subscription;
    private boolean demandOutstanding;
    private boolean itemOutstanding;

    /** Accepts the first upstream subscription. A second subscription must be cancelled by the caller. */
    public synchronized boolean onSubscribe(Flow.Subscription candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (state != State.NEW) {
            return false;
        }
        subscription = candidate;
        state = State.SUBSCRIBED;
        return true;
    }

    /**
     * Returns the upstream subscription to request once, or {@code null} when demand is not safe.
     *
     * <p>Only an adapter that has both a writable channel and a positive available byte budget may
     * pass {@code true} for both arguments.</p>
     */
    public synchronized Flow.Subscription requestNextIfPermitted(boolean channelWritable, boolean byteBudgetAvailable) {
        if (!channelWritable || !byteBudgetAvailable || demandOutstanding || itemOutstanding
                || (state != State.SUBSCRIBED && state != State.ACTIVE)) {
            return null;
        }
        demandOutstanding = true;
        state = State.ACTIVE;
        return subscription;
    }

    /** Records one upstream item; {@code false} indicates a Flow protocol violation. */
    public synchronized boolean onNext() {
        if (state != State.ACTIVE || !demandOutstanding || itemOutstanding) {
            return false;
        }
        demandOutstanding = false;
        itemOutstanding = true;
        return true;
    }

    /**
     * Records upstream completion.
     *
     * @return {@code true} when no prior item remains to be written and the adapter may emit its
     *         terminal wire chunk
     */
    public synchronized boolean onComplete() {
        if (state != State.SUBSCRIBED && state != State.ACTIVE) {
            return false;
        }
        demandOutstanding = false;
        state = State.UPSTREAM_COMPLETED;
        if (!itemOutstanding) {
            state = State.COMPLETED;
            return true;
        }
        return false;
    }

    /**
     * Releases the one item retained by the adapter after its write completion.
     *
     * @return {@code true} when upstream had completed and the adapter may emit its terminal
     *         wire chunk
     */
    public synchronized boolean onItemWritten() {
        if (!itemOutstanding) {
            return false;
        }
        itemOutstanding = false;
        if (state == State.UPSTREAM_COMPLETED) {
            state = State.COMPLETED;
            return true;
        }
        return false;
    }

    /** Marks an upstream failure and returns the subscription to cancel, if one exists. */
    public synchronized Flow.Subscription fail() {
        return terminate(State.FAILED);
    }

    /** Marks transport cancellation and returns the subscription to cancel, if one exists. */
    public synchronized Flow.Subscription cancel() {
        return terminate(State.CANCELLED);
    }

    /** Returns the current state without exposing the upstream subscription. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(state, demandOutstanding, itemOutstanding);
    }

    private Flow.Subscription terminate(State terminal) {
        if (state == State.COMPLETED || state == State.CANCELLED || state == State.FAILED) {
            return null;
        }
        state = terminal;
        demandOutstanding = false;
        itemOutstanding = false;
        return subscription;
    }
}
