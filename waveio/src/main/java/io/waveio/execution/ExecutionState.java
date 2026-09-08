package io.waveio.execution;

/** The lifecycle state of one managed execution. */
public enum ExecutionState {
    /** The execution can accept managed segments. */
    ACTIVE(false),
    /** The execution completed successfully. */
    SUCCEEDED(true),
    /** The execution completed with an application failure. */
    FAILED(true),
    /** The caller or transport cancelled the execution. */
    CANCELLED(true),
    /** The configured deadline ended the execution. */
    TIMED_OUT(true);

    private final boolean terminal;

    ExecutionState(boolean terminal) {
        this.terminal = terminal;
    }

    /** Returns whether this state is terminal and cannot accept further segments. */
    public boolean isTerminal() {
        return terminal;
    }
}
