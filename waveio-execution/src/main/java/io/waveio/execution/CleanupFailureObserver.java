package io.waveio.execution;

/** Observes a failure raised by an execution cleanup action. */
@FunctionalInterface
public interface CleanupFailureObserver {
    /** Records a cleanup failure after the runtime has preserved terminal state. */
    void onCleanupFailure(Throwable failure);
}
