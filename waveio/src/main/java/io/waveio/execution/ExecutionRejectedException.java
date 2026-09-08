package io.waveio.execution;

/** Indicates that a bounded runtime could not accept a new execution or segment. */
public final class ExecutionRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates a rejection exception with an explanatory message. */
    public ExecutionRejectedException(String message) {
        super(message);
    }
}
