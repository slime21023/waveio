package io.wavejava.wave.api.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.RequestContext;
import io.wavejava.wave.api.http.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SseEmitterTest {
    private static final int TIMEOUT_MILLIS = 1_000;

    @Test
    void retainsOnlyBoundedEventsUntilDemandAndGracefullyCompletesAfterDrain() throws Exception {
        var emitter = SseEmitter.builder().maximumQueuedEvents(2).maximumQueuedBytes(128).build();
        assertEquals(SseEmitter.Emission.ACCEPTED, emitter.emit(SseEvent.data("one")));
        assertEquals(SseEmitter.Emission.ACCEPTED, emitter.emit(SseEvent.data("two")));
        assertEquals(2, emitter.snapshot().queuedEvents());

        var subscriber = new RecordingSubscriber();
        emitter.subscribe(subscriber);
        subscriber.request(1);
        assertEquals(List.of("data: one\n\n"), subscriber.items());
        assertEquals(1, emitter.snapshot().queuedEvents());

        assertTrue(emitter.complete());
        assertEquals(SseEmitter.State.CLOSING, emitter.state());
        subscriber.request(1);

        assertEquals(List.of("data: one\n\n", "data: two\n\n"), subscriber.items());
        assertTrue(subscriber.completed.await(1, TimeUnit.SECONDS));
        assertEquals(SseEmitter.State.CLOSED, emitter.state());
        assertFalse(emitter.complete());
    }

    @Test
    void failsTheSlowSubscriberWhenTheExplicitQueueBudgetIsExceeded() throws Exception {
        var emitter = SseEmitter.builder().maximumQueuedEvents(1).maximumQueuedBytes(128).build();
        var subscriber = new RecordingSubscriber();
        emitter.subscribe(subscriber);

        assertEquals(SseEmitter.Emission.ACCEPTED, emitter.emit(SseEvent.data("first")));
        assertEquals(SseEmitter.Emission.REJECTED_OVERFLOW, emitter.emit(SseEvent.data("second")));

        assertTrue(subscriber.failed.await(1, TimeUnit.SECONDS));
        assertInstanceOf(SseOverflowException.class, subscriber.failure.get());
        assertEquals(SseEmitter.State.FAILED, emitter.state());
        assertEquals(0, emitter.snapshot().queuedEvents());
        assertEquals(0, emitter.snapshot().queuedBytes());
        assertEquals(SseEmitter.Emission.REJECTED_CLOSED, emitter.emit(SseEvent.data("late")));
    }

    @Test
    void bindingUsesSseHeadersAndRequestCancellationOnlyCleansEmitterState() {
        var cancellation = CancellationToken.create();
        var request = Request.builder()
                .target("/events")
                .context(RequestContext.builder("sse-test").cancellationToken(cancellation).build())
                .build();
        var response = new io.wavejava.wave.internal.http.InternalResponse();
        var emitter = SseEmitter.create();

        emitter.writeTo(request, response);

        assertEquals("text/event-stream; charset=UTF-8", response.headers().first("Content-Type").orElseThrow());
        assertEquals("no-cache", response.headers().first("Cache-Control").orElseThrow());
        assertTrue(cancellation.cancel("test disconnect"));
        assertEquals(SseEmitter.State.CANCELLED, emitter.state());
        assertEquals(SseEmitter.Emission.REJECTED_CLOSED, emitter.emit(SseEvent.data("late")));
    }

    @Test
    void cancellationBeforeFlowSubscriptionStillTerminatesTheLaterSubscriber() throws Exception {
        var cancellation = CancellationToken.create();
        var request = Request.builder()
                .target("/events")
                .context(RequestContext.builder("sse-pre-subscribe").cancellationToken(cancellation).build())
                .build();
        var emitter = SseEmitter.create();

        emitter.writeTo(request, new io.wavejava.wave.internal.http.InternalResponse());
        assertTrue(cancellation.cancel("client disconnected before stream subscribe"));

        var subscriber = new RecordingSubscriber();
        emitter.subscribe(subscriber);

        assertTrue(subscriber.failed.await(1, TimeUnit.SECONDS),
                "a cancelled emitter must not leave a later response Flow subscriber open");
        assertInstanceOf(java.util.concurrent.CancellationException.class, subscriber.failure.get());
        assertEquals(SseEmitter.State.CANCELLED, emitter.state());
    }

    @Test
    void requestCancellationDefersSubscriberTerminalAwayFromTheCancellingThread() throws Exception {
        var cancellation = CancellationToken.create();
        var request = Request.builder()
                .target("/events")
                .context(RequestContext.builder("sse-off-transport-terminal")
                        .cancellationToken(cancellation)
                        .build())
                .build();
        var emitter = SseEmitter.create();
        var subscriber = new RecordingSubscriber();
        emitter.writeTo(request, new io.wavejava.wave.internal.http.InternalResponse());
        emitter.subscribe(subscriber);

        var cancellingThread = Thread.ofPlatform()
                .name("simulated-netty-event-loop")
                .start(() -> cancellation.cancel("client disconnect"));
        cancellingThread.join(TIMEOUT_MILLIS);

        assertTrue(subscriber.failed.await(1, TimeUnit.SECONDS));
        assertNotSame(cancellingThread, subscriber.terminalThread.get(),
                "request cancellation must not invoke Flow subscriber code on the transport thread");
    }

    @Test
    void emitsHeartbeatsOnlyWhileOpenAndCancelsItsOwnSchedulerTask() throws Exception {
        var scheduler = new ManualHeartbeatScheduler();
        try {
            var emitter = SseEmitter.builder()
                    .maximumQueuedEvents(4)
                    .maximumQueuedBytes(128)
                    .heartbeat(Duration.ofSeconds(1), scheduler)
                    .build();
            var subscriber = new RecordingSubscriber();
            emitter.subscribe(subscriber);
            assertTrue(emitter.snapshot().heartbeatScheduled());
            subscriber.request(1);

            scheduler.fire();
            assertEquals(List.of(":\n\n"), subscriber.items());
            emitter.close();
            assertTrue(subscriber.completed.await(0, TimeUnit.MILLISECONDS));
            assertTrue(scheduler.heartbeatTaskCancelled());
            assertFalse(emitter.snapshot().heartbeatScheduled());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void explicitAbortSignalsTheFlowSubscriberAndDropsQueuedBytes() throws Exception {
        var emitter = SseEmitter.create();
        var subscriber = new RecordingSubscriber();
        emitter.subscribe(subscriber);
        assertEquals(SseEmitter.Emission.ACCEPTED, emitter.emit(SseEvent.data("queued")));

        assertTrue(emitter.abort());

        assertTrue(subscriber.failed.await(1, TimeUnit.SECONDS));
        assertInstanceOf(java.util.concurrent.CancellationException.class, subscriber.failure.get());
        assertEquals(0, emitter.snapshot().queuedBytes());
    }

    @Test
    void rejectsSecondSubscriberInvalidDemandAndIncompatibleResponseContentType() throws Exception {
        var emitter = SseEmitter.create();
        var first = new RecordingSubscriber();
        emitter.subscribe(first);
        var second = new RecordingSubscriber();
        emitter.subscribe(second);
        assertTrue(second.failed.await(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, second.failure.get());

        first.request(0);
        assertTrue(first.failed.await(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, first.failure.get());
        assertEquals(SseEmitter.State.FAILED, emitter.state());

        var incompatible = SseEmitter.create();
        var response = new io.wavejava.wave.internal.http.InternalResponse().header("Content-Type", "application/json");
        assertThrows(IllegalStateException.class, () -> incompatible.writeTo(Request.of(io.wavejava.wave.api.http.HttpMethod.GET, "/events"), response));
        assertFalse(incompatible.snapshot().subscriberAttached());
    }

    @Test
    void completedEmitterClosesALaterSubscriberAndDoesNotOverwriteExistingCachePolicy() throws Exception {
        var emitter = SseEmitter.create();
        assertTrue(emitter.complete());
        var subscriber = new RecordingSubscriber();
        emitter.subscribe(subscriber);
        assertTrue(subscriber.completed.await(1, TimeUnit.SECONDS));
        assertEquals(SseEmitter.State.CLOSED, emitter.state());

        var attached = SseEmitter.create();
        var response = new io.wavejava.wave.internal.http.InternalResponse().header("Cache-Control", "private");
        attached.writeTo(Request.of(io.wavejava.wave.api.http.HttpMethod.GET, "/events"), response);
        assertEquals("private", response.headers().first("Cache-Control").orElseThrow());
        assertThrows(IllegalStateException.class,
                () -> attached.writeTo(Request.of(io.wavejava.wave.api.http.HttpMethod.GET, "/again"),
                        new io.wavejava.wave.internal.http.InternalResponse()));
        attached.abort();
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final List<String> items = new ArrayList<>();
        private final CountDownLatch itemArrived = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final CountDownLatch failed = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Thread> terminalThread = new AtomicReference<>();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        @Override
        public void onNext(ByteBuffer item) {
            var copy = new byte[item.remaining()];
            item.get(copy);
            items.add(new String(copy, StandardCharsets.UTF_8));
            itemArrived.countDown();
        }

        @Override
        public void onError(Throwable failure) {
            this.failure.set(failure);
            terminalThread.set(Thread.currentThread());
            failed.countDown();
        }

        @Override
        public void onComplete() {
            terminalThread.set(Thread.currentThread());
            completed.countDown();
        }

        void request(long demand) {
            subscription.request(demand);
        }

        List<String> items() {
            return List.copyOf(items);
        }
    }

    /** A no-thread heartbeat scheduler: the test controls precisely when a periodic task runs. */
    private static final class ManualHeartbeatScheduler extends ScheduledThreadPoolExecutor {
        private Runnable heartbeat;
        private ManualScheduledFuture heartbeatTask;

        private ManualHeartbeatScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            heartbeat = command;
            heartbeatTask = new ManualScheduledFuture();
            return heartbeatTask;
        }

        void fire() {
            if (heartbeat == null || heartbeatTask == null) {
                throw new AssertionError("no heartbeat task was scheduled");
            }
            if (!heartbeatTask.isCancelled()) {
                heartbeat.run();
            }
        }

        boolean heartbeatTaskCancelled() {
            return heartbeatTask != null && heartbeatTask.isCancelled();
        }
    }

    /** Minimal periodic future used only by {@link ManualHeartbeatScheduler}. */
    private static final class ManualScheduledFuture implements ScheduledFuture<Void> {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Void get() {
            return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
