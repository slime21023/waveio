package io.wavejava.wave.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FlowSubscriptionControllerTest {
    @Test
    void permitsOnlyOneDemandedAndUnwrittenItemAtATime() {
        var controller = new FlowSubscriptionController();
        var subscription = new RecordingSubscription();

        assertTrue(controller.onSubscribe(subscription));
        assertNull(controller.requestNextIfPermitted(false, true));
        assertNull(controller.requestNextIfPermitted(true, false));
        assertSame(subscription, controller.requestNextIfPermitted(true, true));
        assertNull(controller.requestNextIfPermitted(true, true));
        assertTrue(controller.onNext());
        assertNull(controller.requestNextIfPermitted(true, true));
        assertFalse(controller.onComplete());
        assertTrue(controller.onItemWritten());
        assertEquals(FlowSubscriptionController.State.COMPLETED, controller.snapshot().state());
        assertNull(controller.cancel());
    }

    @Test
    void rejectsUnsolicitedItemsAndReturnsSubscriptionForCancellation() {
        var controller = new FlowSubscriptionController();
        var subscription = new RecordingSubscription();

        assertTrue(controller.onSubscribe(subscription));
        assertFalse(controller.onNext());
        assertSame(subscription, controller.fail());
        assertEquals(FlowSubscriptionController.State.FAILED, controller.snapshot().state());
        assertFalse(controller.onSubscribe(new RecordingSubscription()));
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private final AtomicInteger requested = new AtomicInteger();

        @Override
        public void request(long demand) {
            requested.addAndGet(Math.toIntExact(demand));
        }

        @Override
        public void cancel() {
            // The controller does not invoke user callbacks directly.
        }
    }
}
