package io.wavejava.wave.api.http;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A thread-safe, one-way cancellation signal for one request invocation.
 *
 * <p>The first successful {@link #cancel(String)} call wins and permanently records its reason.
 * Subsequent cancellation attempts do not replace that reason. Cancellation is cooperative: a
 * caller observes it through {@link #isCancelled()} or {@link #reason()} and decides how to stop
 * its own work.</p>
 */
public final class CancellationToken {
    private final AtomicReference<String> reason = new AtomicReference<>();
    private final Set<RegistrationImpl> registrations = ConcurrentHashMap.newKeySet();

    /** Creates an active cancellation token. */
    public CancellationToken() {
    }

    /** Creates an active cancellation token. */
    public static CancellationToken create() {
        return new CancellationToken();
    }

    /**
     * Cancels this token with a non-blank diagnostic reason.
     *
     * @return {@code true} only for the caller that changed this token from active to cancelled
     */
    public boolean cancel(String reason) {
        validateReason(reason);
        if (!this.reason.compareAndSet(null, reason)) {
            return false;
        }
        for (var registration : registrations) {
            registration.notifyCancellation(reason);
        }
        registrations.clear();
        return true;
    }

    /** Returns whether this token has been cancelled. */
    public boolean isCancelled() {
        return reason.get() != null;
    }

    /** Returns the reason recorded by the first successful cancellation, if any. */
    public Optional<String> reason() {
        return Optional.ofNullable(reason.get());
    }

    /**
     * Registers work that must run when this token is cancelled.
     *
     * <p>The callback runs at most once, on the thread which wins cancellation (or immediately on
     * the registering thread when cancellation has already happened). It must return promptly;
     * long-running cleanup should arrange its own asynchronous work. Closing the returned
     * registration prevents a still-pending callback and releases its reference from this token.
     * Exceptions thrown by a callback are isolated so that one listener cannot prevent the
     * remaining cancellation cleanup from running.</p>
     *
     * @param listener receives the winning non-blank cancellation reason
     * @return a handle that unregisters this listener when closed
     */
    public Registration onCancellation(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        var registration = new RegistrationImpl(this, listener);
        var currentReason = reason.get();
        if (currentReason != null) {
            registration.notifyCancellation(currentReason);
            return registration;
        }

        registrations.add(registration);
        // Cancellation may have won between the first read and the registration. The registration
        // state makes this race deliver exactly one callback whether this thread or cancel() wins.
        currentReason = reason.get();
        if (currentReason != null) {
            registration.notifyCancellation(currentReason);
        }
        return registration;
    }

    /** A removable cancellation callback registration. */
    public interface Registration extends AutoCloseable {
        /** Removes a pending callback. Repeated calls are harmless. */
        @Override
        void close();

        /** Returns whether this callback can still run. */
        boolean isActive();
    }

    private static void validateReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason must not be blank");
        }
    }

    private static final class RegistrationImpl implements Registration {
        private final CancellationToken owner;
        private final Consumer<String> listener;
        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

        private RegistrationImpl(CancellationToken owner, Consumer<String> listener) {
            this.owner = owner;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (state.compareAndSet(State.ACTIVE, State.CLOSED)) {
                owner.registrations.remove(this);
            }
        }

        @Override
        public boolean isActive() {
            return state.get() == State.ACTIVE;
        }

        private void notifyCancellation(String reason) {
            if (!state.compareAndSet(State.ACTIVE, State.CANCELLED)) {
                return;
            }
            owner.registrations.remove(this);
            try {
                listener.accept(reason);
            } catch (RuntimeException ignored) {
                // A cancellation signal must finish notifying independent cleanup owners.
            }
        }

        private enum State {
            ACTIVE,
            CANCELLED,
            CLOSED
        }
    }
}
