package io.wavejava.wave.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutboundByteBudgetTest {
    @Test
    void reservesOnlyFiniteInFlightBytesAndDropsAccountingOnClose() {
        var budget = new OutboundByteBudget(5);

        assertTrue(budget.tryReserve(3).accepted());
        assertEquals(2, budget.snapshot().availableBytes());
        assertFalse(budget.tryReserve(3).accepted());
        assertTrue(budget.release(3));
        assertEquals(5, budget.snapshot().availableBytes());
        assertTrue(budget.close().closed());
        assertFalse(budget.tryReserve(1).accepted());
        assertFalse(budget.release(0));
        assertThrows(IllegalArgumentException.class, () -> new OutboundByteBudget(0));
    }
}
