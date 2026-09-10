package io.wavejava.wave.runtime;

import io.wavejava.wave.api.http.Request;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates an interruptible application task and records its execution observations. */
final class CancellationProbe {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<Request> request = new AtomicReference<>();
    private final AtomicReference<Thread> executionThread = new AtomicReference<>();

    /** Returns an application task that blocks until released or interrupted. */
    <T> InvocationRuntime.RequestTask<T> blockingTask(T releasedResult, T interruptedResult) {
        return received -> {
            request.set(received);
            executionThread.set(Thread.currentThread());
            received.cancellationToken().orElseThrow();
            entered.countDown();
            try {
                release.await();
                return releasedResult;
            } catch (InterruptedException expected) {
                interrupted.countDown();
                return interruptedResult;
            }
        };
    }

    boolean awaitEntered(Duration timeout) throws InterruptedException {
        return entered.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    boolean awaitInterrupted(Duration timeout) throws InterruptedException {
        return interrupted.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    Optional<Request> request() {
        return Optional.ofNullable(request.get());
    }

    Optional<Thread> executionThread() {
        return Optional.ofNullable(executionThread.get());
    }

    /** Lets an otherwise active task complete normally. */
    void release() {
        release.countDown();
    }
}
