package io.wavejava.wave.runtime.client;

import io.wavejava.wave.api.client.ClientCancelledException;
import io.wavejava.wave.api.client.ClientTimeoutException;
import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Deadline;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Blocking bridge for a completion stage that preserves interruption and observes wave deadlines
 * and cancellation tokens.
 */
public final class BlockingResultWaiter {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    private BlockingResultWaiter() {
    }

    /** Waits for a completion stage and converts checked completion failures to CompletionException. */
    public static <T> T await(CompletionStage<T> stage) {
        return await(stage, null, null);
    }

    /**
     * Waits while observing an optional deadline and cancellation token.
     *
     * <p>Deadline or cancellation causes cancel the underlying future when possible. An
     * interruption likewise cancels it and restores the calling thread's interrupted status.</p>
     */
    public static <T> T await(CompletionStage<T> stage, Deadline deadline, CancellationToken cancellationToken) {
        Objects.requireNonNull(stage, "stage");
        var future = stage.toCompletableFuture();
        while (true) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                future.cancel(true);
                throw new ClientCancelledException(cancellationToken.reason().orElse("client cancellation requested"));
            }
            if (deadline != null && deadline.isExpired()) {
                if (cancellationToken != null) {
                    cancellationToken.cancel("client deadline exceeded");
                }
                future.cancel(true);
                throw new ClientTimeoutException(Duration.ZERO, null);
            }
            var wait = remainingWait(deadline);
            try {
                return future.get(wait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException ignored) {
                // Re-check wave cancellation/deadline after at most one short poll interval.
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new ClientCancelledException("blocking client await interrupted");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw new CompletionException(failure.getCause());
            } catch (CancellationException failure) {
                throw failure;
            }
        }
    }

    private static Duration remainingWait(Deadline deadline) {
        if (deadline == null) {
            return POLL_INTERVAL;
        }
        var remaining = deadline.remaining();
        return remaining.compareTo(POLL_INTERVAL) < 0 ? remaining : POLL_INTERVAL;
    }
}


