package io.wavejava.wave.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PendingResponseBudgetTest {
    @Test
    void countCapRejectsIngressAndBecomesWritableAfterRelease() {
        var budget = new PendingResponseBudget(2, 10);

        assertTrue(budget.tryAcquireResponse().accepted());
        assertTrue(budget.tryAcquireResponse().accepted());

        var rejected = budget.tryAcquireResponse();
        assertTrue(rejected.rejected());
        assertEquals(PendingResponseBudget.Rejection.PENDING_RESPONSE_COUNT, rejected.rejection());
        assertEquals(2, rejected.snapshot().pendingResponses());
        assertTrue(rejected.snapshot().shouldPauseReads());

        var released = budget.releaseResponse(0);
        assertEquals(1, released.pendingResponses());
        assertFalse(released.shouldPauseReads());
        assertTrue(budget.tryAcquireResponse().accepted());
    }

    @Test
    void byteCapIsInclusiveAndNeverOverflowsAccounting() {
        var budget = new PendingResponseBudget(3, 10);
        budget.tryAcquireResponse();
        budget.tryAcquireResponse();

        assertTrue(budget.tryReserveResponseBytes(7).accepted());
        var atLimit = budget.tryReserveResponseBytes(3);
        assertTrue(atLimit.accepted());
        assertTrue(atLimit.snapshot().shouldPauseReads());

        var rejected = budget.tryReserveResponseBytes(1);
        assertTrue(rejected.rejected());
        assertEquals(PendingResponseBudget.Rejection.BUFFERED_RESPONSE_BYTES, rejected.rejection());
        assertEquals(10, rejected.snapshot().pendingResponseBytes());

        var afterFirstWrite = budget.releaseResponse(7);
        assertEquals(3, afterFirstWrite.pendingResponseBytes());
        assertFalse(afterFirstWrite.shouldPauseReads());
        var empty = budget.releaseResponse(3);
        assertEquals(0, empty.pendingResponses());
        assertEquals(0, empty.pendingResponseBytes());
    }

    @Test
    void invalidLimitsAndReleasesFailWithoutCorruptingState() {
        assertThrows(IllegalArgumentException.class, () -> new PendingResponseBudget(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PendingResponseBudget(1, 0));

        var budget = new PendingResponseBudget(1, 5);
        budget.tryAcquireResponse();
        budget.tryReserveResponseBytes(3);

        assertThrows(IllegalArgumentException.class, () -> budget.tryReserveResponseBytes(-1));
        assertThrows(IllegalStateException.class, () -> budget.releaseResponse(4));
        var unchanged = budget.snapshot();
        assertEquals(1, unchanged.pendingResponses());
        assertEquals(3, unchanged.pendingResponseBytes());

        budget.releaseResponse(3);
        assertThrows(IllegalStateException.class, () -> budget.releaseResponse(0));
    }
}
