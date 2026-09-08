package io.waveio.server;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded asynchronous dispatcher that isolates observer failures from callers. */
public final class ObserverDispatcher implements AutoCloseable {
    private final List<Observer> observers;
    private final ThreadPoolExecutor executor;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /** Creates a dispatcher with explicit worker and waiting capacities. */
    public ObserverDispatcher(List<? extends Observer> observers, int parallelism, int queueCapacity) {
        Objects.requireNonNull(observers, "observers");
        if (parallelism < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("parallelism and queueCapacity must be positive");
        }
        this.observers = List.copyOf(observers);
        if (this.observers.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("observer");
        }
        this.executor = new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
    }

    /** Attempts bounded asynchronous delivery and returns false when the dispatcher is saturated or closed. */
    public boolean submit(ObservationEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            executor.execute(() -> deliver(event));
            return true;
        } catch (RejectedExecutionException exception) {
            rejected.incrementAndGet();
            return false;
        }
    }

    /** Returns a stable immutable snapshot of delivery outcomes. */
    public ObservationMetrics metrics() {
        return new ObservationMetrics(delivered.get(), rejected.get(), failures.get());
    }

    private void deliver(ObservationEvent event) {
        for (Observer observer : observers) {
            try {
                observer.onEvent(event);
                delivered.incrementAndGet();
            } catch (RuntimeException exception) {
                failures.incrementAndGet();
            }
        }
    }

    /** Stops accepting work and interrupts outstanding observer callbacks. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
