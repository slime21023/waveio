package io.wavejava.wave.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShutdownCoordinatorTest {
    @Test
    void shutdownIsOneWayAndBeginsOnlyOnce() {
        var coordinator = new ShutdownCoordinator();

        assertEquals(ShutdownCoordinator.State.RUNNING, coordinator.state());
        assertTrue(coordinator.isRunning());
        assertTrue(coordinator.beginShutdown());
        assertFalse(coordinator.beginShutdown());
        assertEquals(ShutdownCoordinator.State.SHUTTING_DOWN, coordinator.state());
        assertFalse(coordinator.isRunning());

        coordinator.completeShutdown();

        assertEquals(ShutdownCoordinator.State.TERMINATED, coordinator.state());
        assertFalse(coordinator.beginShutdown());
    }
}
