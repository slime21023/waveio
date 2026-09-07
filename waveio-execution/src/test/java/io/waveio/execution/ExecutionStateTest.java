package io.waveio.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExecutionStateTest {
    @Test
    void activeIsTheOnlyNonTerminalState() {
        assertFalse(ExecutionState.ACTIVE.isTerminal());
        assertTrue(ExecutionState.SUCCEEDED.isTerminal());
        assertTrue(ExecutionState.FAILED.isTerminal());
        assertTrue(ExecutionState.CANCELLED.isTerminal());
        assertTrue(ExecutionState.TIMED_OUT.isTerminal());
    }
}
