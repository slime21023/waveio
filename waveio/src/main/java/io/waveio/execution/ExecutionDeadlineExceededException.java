package io.waveio.execution;

/** Indicates that an execution exceeded its configured deadline. */
public final class ExecutionDeadlineExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates a deadline exception. */
    public ExecutionDeadlineExceededException() {
        super("execution deadline exceeded");
    }
}
