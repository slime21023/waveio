package io.waveio.execution;

/** Indicates that an execution was cancelled before producing its result. */
public final class ExecutionCancelledException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates a cancellation exception. */
    public ExecutionCancelledException() {
        super("execution was cancelled");
    }
}
