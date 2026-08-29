package io.waveio.http.testing;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class Await {
    private Await() {}

    public static void until(BooleanSupplier condition, Duration timeout, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        if (!condition.getAsBoolean()) fail(message);
    }
}
