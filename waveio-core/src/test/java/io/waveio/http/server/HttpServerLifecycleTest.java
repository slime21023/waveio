package io.waveio.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HttpServerLifecycleTest {

    @Test
    void cannotStartTwice() {
        var server = HttpServer.builder().port(0).build();
        server.start();
        try {
            assertThrows(IllegalStateException.class, server::start);
        } finally {
            server.stop();
        }
    }

    @Test
    void cannotQueryLocalPortBeforeStarting() {
        var server = HttpServer.builder().port(0).build();
        assertThrows(IllegalStateException.class, server::localPort);
        assertFalse(server.isRunning());
    }

    @Test
    void cannotAwaitTerminationBeforeStarting() {
        var server = HttpServer.builder().port(0).build();
        assertThrows(IllegalStateException.class, server::awaitTermination);
    }

    @Test
    void stopIsIdempotent() {
        var server = HttpServer.builder().port(0).build();
        server.start();
        server.stop();
        server.stop(); // Second call is a no-op
        assertFalse(server.isRunning());
    }

    @Test
    void awaitTerminationHandlesInterruption() throws Exception {
        var server = HttpServer.builder().port(0).build().start();
        var interruptedCaught = new AtomicBoolean();
        var latch = new CountDownLatch(1);

        var thread = Thread.startVirtualThread(() -> {
            latch.countDown();
            try {
                server.awaitTermination();
            } catch (InterruptedException e) {
                interruptedCaught.set(true);
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        thread.interrupt();
        thread.join(5_000);

        assertTrue(interruptedCaught.get());
        server.stop();
    }

    @Test
    void failsToStartOnPortConflict() {
        var server1 = HttpServer.builder().port(0).build().start();
        try {
            int port = server1.localPort();
            var server2 = HttpServer.builder().port(port).build();
            assertThrows(Exception.class, server2::start);
            assertFalse(server2.isRunning());
        } finally {
            server1.stop();
        }
    }
}
