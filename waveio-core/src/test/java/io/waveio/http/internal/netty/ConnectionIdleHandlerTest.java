package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConnectionIdleHandlerTest {

    @Test
    void closesAConnectionAfterTheConfiguredIdlePeriod() {
        var handler = new ConnectionIdleHandler(Duration.ofMillis(100));
        var channel = new EmbeddedChannel(handler);

        channel.advanceTimeBy(99, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(channel.isActive());

        channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());
    }

    @Test
    void activeRequestIsExemptAndGetsAFreshIdlePeriodAfterCompletion() {
        var handler = new ConnectionIdleHandler(Duration.ofMillis(100));
        var channel = new EmbeddedChannel(handler);
        var context = channel.pipeline().context(handler);

        handler.requestStarted(context);
        channel.advanceTimeBy(500, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(channel.isActive());

        handler.requestFinished(context);
        channel.advanceTimeBy(99, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(channel.isActive());

        channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());
    }

    @Test
    void inboundTrafficRestartsTheIdlePeriod() {
        var handler = new ConnectionIdleHandler(Duration.ofMillis(100));
        var channel = new EmbeddedChannel(handler);

        channel.advanceTimeBy(75, TimeUnit.MILLISECONDS);
        channel.writeInbound("data");
        channel.advanceTimeBy(75, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(channel.isActive());

        channel.advanceTimeBy(25, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());
    }
}
