package io.wavejava.wave.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.Deadline;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.RequestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InvocationRuntimeTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void submitRunsOnAVirtualThreadAndAttachesTheSuppliedContext() throws Exception {
        try (var harness = ExecutionHarness.at(NOW)) {
            var runtime = harness.runtime();
            var token = CancellationToken.create();
            var context = RequestContext.builder("request-1").cancellationToken(token).build();
            var invoked = new CountDownLatch(1);
            var isVirtual = new AtomicBoolean();
            var seenRequest = new AtomicReference<Request>();

            var invocation = runtime.submit(Request.of(HttpMethod.GET, "/runtime"), context, request -> {
                seenRequest.set(request);
                isVirtual.set(Thread.currentThread().isVirtual());
                invoked.countDown();
                return "done";
            });

            assertTrue(invoked.await(ASSERTION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "application task should run");
            assertTrue(isVirtual.get(), "application task must not run on a transport event loop");
            assertSame(context, seenRequest.get().context().orElseThrow());
            assertEquals("done", invocation.completion().toCompletableFuture().get(ASSERTION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));
            assertEquals(RequestInvocation.State.SUCCEEDED, invocation.state());
        }
    }

    @Test
    void deadlineCancellationSignalsTokenAndInterruptsTheAssociatedVirtualThread() throws Exception {
        try (var harness = ExecutionHarness.at(NOW)) {
            var runtime = harness.runtime();
            var token = CancellationToken.create();
            var context = RequestContext.builder("request-deadline")
                    .cancellationToken(token)
                    .deadline(Deadline.at(NOW.plusSeconds(1)))
                    .build();
            var probe = new CancellationProbe();

            var invocation = runtime.submit(
                    Request.of(HttpMethod.GET, "/deadline"),
                    context,
                    probe.blockingTask("released", "interrupted"));

            assertTrue(probe.awaitEntered(ASSERTION_TIMEOUT), "task should start before deadline delivery");
            assertEquals(1, harness.scheduler().scheduledTaskCount());
            assertFalse(token.isCancelled(), "deadline cannot fire until test time advances");
            harness.advanceBy(Duration.ofSeconds(1));

            assertTrue(probe.awaitInterrupted(ASSERTION_TIMEOUT), "deadline must interrupt the running virtual thread");
            assertSame(token, probe.request().orElseThrow().cancellationToken().orElseThrow());
            assertTrue(probe.executionThread().orElseThrow().isVirtual());
            assertTrue(token.isCancelled());
            assertEquals("deadline exceeded", token.reason().orElseThrow());
            assertTrue(invocation.isCancelled());
            assertEquals(RequestInvocation.State.CANCELLED, invocation.state());
            assertThrows(CancellationException.class, () -> invocation.completion().toCompletableFuture().join());
            probe.release();
        }
    }

    @Test
    void completionAndCancellationRaceChooseExactlyOneTerminalOutcome() throws Exception {
        try (var harness = ExecutionHarness.at(NOW)) {
            var runtime = harness.runtime();
            var token = CancellationToken.create();
            var context = RequestContext.builder("request-race").cancellationToken(token).build();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var terminal = new CountDownLatch(1);
            var terminalCallbacks = new AtomicInteger();
            var cancellationWon = new AtomicBoolean();

            var invocation = runtime.submit(Request.of(HttpMethod.GET, "/race"), context, request -> {
                entered.countDown();
                release.await();
                return "completed";
            });
            invocation.completion().whenComplete((ignored, failure) -> {
                terminalCallbacks.incrementAndGet();
                terminal.countDown();
            });
            assertTrue(entered.await(ASSERTION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "task should be ready to race");

            var contendersReady = new CountDownLatch(2);
            var startRace = new CountDownLatch(1);
            var contendersDone = new CountDownLatch(2);
            try (var contenders = Executors.newVirtualThreadPerTaskExecutor()) {
                contenders.submit(() -> {
                    contendersReady.countDown();
                    startRace.await();
                    cancellationWon.set(runtime.cancel(invocation, "client disconnected"));
                    contendersDone.countDown();
                    return null;
                });
                contenders.submit(() -> {
                    contendersReady.countDown();
                    startRace.await();
                    release.countDown();
                    contendersDone.countDown();
                    return null;
                });

                assertTrue(contendersReady.await(ASSERTION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "race contenders should be ready");
                startRace.countDown();
                assertTrue(contendersDone.await(ASSERTION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "race contenders should finish");
            }

            assertTrue(terminal.await(ASSERTION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS), "exactly one terminal completion should occur");
            assertEquals(1, terminalCallbacks.get());
            if (cancellationWon.get()) {
                assertTrue(token.isCancelled());
                assertThrows(CancellationException.class, () -> invocation.completion().toCompletableFuture().join());
            } else {
                assertEquals("completed", invocation.completion().toCompletableFuture().get(ASSERTION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));
                assertFalse(token.isCancelled());
            }
            assertFalse(runtime.cancel(invocation, "too late"));
        }
    }

    @Test
    void shutdownCancelsActiveWorkAndRejectsLaterSubmissions() throws Exception {
        try (var harness = ExecutionHarness.at(NOW)) {
            var runtime = harness.runtime();
            var token = CancellationToken.create();
            var context = RequestContext.builder("request-shutdown").cancellationToken(token).build();
            var probe = new CancellationProbe();

            var invocation = runtime.submit(
                    Request.of(HttpMethod.GET, "/shutdown"),
                    context,
                    probe.blockingTask("unreachable", "cancelled"));
            assertTrue(probe.awaitEntered(ASSERTION_TIMEOUT), "task should start before shutdown");

            runtime.shutdown();

            assertTrue(probe.awaitInterrupted(ASSERTION_TIMEOUT), "shutdown must interrupt active work");
            assertSame(token, probe.request().orElseThrow().cancellationToken().orElseThrow());
            assertTrue(token.isCancelled());
            assertEquals("server shutdown", token.reason().orElseThrow());
            assertTrue(invocation.isCancelled());
            assertThrows(
                    RejectedExecutionException.class,
                    () -> runtime.submit(Request.of(HttpMethod.GET, "/later"), RequestContext.of("later"), request -> "nope"));
            assertTrue(runtime.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @Test
    void preAttachedContextCanBeSubmittedAndContextIsRequiredOtherwise() throws Exception {
        try (var harness = ExecutionHarness.at(NOW)) {
            var runtime = harness.runtime();
            var context = RequestContext.of("request-attached");
            var request = Request.of(HttpMethod.GET, "/attached").withContext(context);

            var invocation = runtime.submit(request, received -> {
                assertSame(context, received.context().orElseThrow());
                return "ok";
            });

            assertEquals("ok", invocation.completion().toCompletableFuture().get(ASSERTION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.submit(Request.of(HttpMethod.GET, "/missing"), received -> "never"));
        }
    }

}
