package io.wavejava.wave.api.resilience;

import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.middleware.Middleware;
import io.wavejava.wave.api.middleware.Outcome;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Semaphore;

/**
 * Zero-queue concurrency bulkhead for synchronous Wave application invocation scopes.
 *
 * <p>A permit is acquired in {@link #onRequest(Request, Response)} and released by the guaranteed
 * reverse middleware unwind in {@link #onResponse(Request, Response, Outcome)}. It intentionally
 * remains held while a cancelled handler has not yet unwound, so cancellation cannot over-admit
 * work that is still using its application resources.</p>
 */
public final class BulkheadPolicy implements Middleware {
    private final int maximumConcurrent;
    private final Semaphore permits;
    private final ThreadLocal<Deque<Boolean>> entered = ThreadLocal.withInitial(ArrayDeque::new);

    /** Creates a finite no-queue bulkhead. */
    public BulkheadPolicy(int maximumConcurrent) {
        if (maximumConcurrent <= 0) {
            throw new IllegalArgumentException("maximumConcurrent must be greater than zero");
        }
        this.maximumConcurrent = maximumConcurrent;
        permits = new Semaphore(maximumConcurrent, true);
    }

    /** Returns the finite maximum number of admitted application invocations. */
    public int maximumConcurrent() {
        return maximumConcurrent;
    }

    /** Returns the currently held permit count for diagnostics/tests. */
    public int activeInvocations() {
        return maximumConcurrent - permits.availablePermits();
    }

    @Override
    public void onRequest(Request request, Response response) {
        var admitted = permits.tryAcquire();
        entered.get().push(admitted);
        if (!admitted) {
            response.problem(Problem.of(503, "Service Unavailable"));
        }
    }

    @Override
    public void onResponse(Request request, Response response, Outcome outcome) {
        var stack = entered.get();
        if (stack.isEmpty()) {
            return;
        }
        if (stack.pop()) {
            permits.release();
        }
        if (stack.isEmpty()) {
            entered.remove();
        }
    }
}
