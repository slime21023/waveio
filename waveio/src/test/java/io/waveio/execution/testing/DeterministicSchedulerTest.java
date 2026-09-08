package io.waveio.execution.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicSchedulerTest {
    @Test
    void runsCallbacksOnlyWhenTheirManualDeadlineIsDueInFifoOrder() {
        ManualClock clock = new ManualClock(Instant.EPOCH);
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        List<String> observed = new ArrayList<>();
        scheduler.schedule(Duration.ofSeconds(2), () -> observed.add("later"));
        scheduler.execute(() -> observed.add("first"));
        scheduler.execute(() -> observed.add("second"));

        assertEquals(2, scheduler.runDue());
        assertEquals(List.of("first", "second"), observed);
        assertEquals(0, scheduler.advanceAndRun(Duration.ofSeconds(1)));
        assertEquals(1, scheduler.advanceAndRun(Duration.ofSeconds(1)));
        assertEquals(List.of("first", "second", "later"), observed);
    }

    @Test
    void rejectsBackwardsTestTime() {
        ManualClock clock = new ManualClock(Instant.EPOCH);
        DeterministicScheduler scheduler = new DeterministicScheduler(clock);
        assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(Duration.ofNanos(-1), () -> { }));
    }
}
