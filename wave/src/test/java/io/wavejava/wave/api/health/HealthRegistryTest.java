package io.wavejava.wave.api.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HealthRegistryTest {
    @Test
    void readinessRequiresLifecycleReadyAndEveryRequiredCheckToBeUp() {
        var registry = HealthRegistry.builder()
                .check(check("database", HealthStatus.up("database reachable")))
                .check(check("cache", HealthStatus.down("cache unavailable")))
                .build();

        assertTrue(registry.liveness().isUp());
        assertFalse(registry.readiness().isUp());
        assertEquals("STARTING", registry.readiness().details().get("lifecycle"));

        registry.markReady();
        var down = registry.readiness();
        assertFalse(down.isUp());
        assertEquals("cache", down.details().get("check"));
        registry.markStopping();
        assertFalse(registry.liveness().isUp());
        assertFalse(registry.readiness().isUp());
    }

    @Test
    void healthCheckTimeoutCancelsTheCheckInsteadOfRetainingIt() throws Exception {
        var cancelled = new CountDownLatch(1);
        var never = new CompletableFuture<HealthStatus>();
        var registry = HealthRegistry.builder()
                .limits(new HealthLimits(Duration.ofMillis(40), 2, 1))
                .check(new HealthCheck() {
                    @Override
                    public String name() {
                        return "blocked";
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<HealthStatus> check(HealthCheckContext context) {
                        context.cancellationToken().onCancellation(reason -> cancelled.countDown());
                        return never;
                    }
                })
                .build();
        registry.markReady();

        var status = registry.readiness();
        assertFalse(status.isUp());
        assertEquals("blocked", status.details().get("check"));
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertTrue(never.isCancelled());
    }

    @Test
    void concurrentHealthEvaluationHasNoQueueAndReportsBoundedOverload() throws Exception {
        var entered = new CountDownLatch(1);
        var completion = new CompletableFuture<HealthStatus>();
        var registry = HealthRegistry.builder()
                .limits(new HealthLimits(Duration.ofSeconds(2), 2, 1))
                .check(new HealthCheck() {
                    @Override
                    public String name() {
                        return "slow";
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<HealthStatus> check(HealthCheckContext context) {
                        entered.countDown();
                        return completion;
                    }
                })
                .build();
        registry.markReady();
        var first = new AtomicReference<HealthStatus>();
        var worker = Thread.ofVirtual().start(() -> first.set(registry.readiness()));
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        var overloaded = registry.readiness();
        assertFalse(overloaded.isUp());
        assertEquals("health evaluation capacity is exhausted", overloaded.summary());
        completion.complete(HealthStatus.up("slow check complete"));
        worker.join(TimeUnit.SECONDS.toMillis(1));
        assertTrue(first.get().isUp());
    }

    @Test
    void statusAndRegistryBuildersRejectUnboundedOrAmbiguousDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> HealthStatus.up("x\nunsafe"));
        assertThrows(IllegalArgumentException.class,
                () -> new HealthLimits(Duration.ZERO, 1, 1));
        assertThrows(IllegalStateException.class, () -> HealthRegistry.builder()
                .check(check("same", HealthStatus.up("one")))
                .check(check("same", HealthStatus.up("two")))
                .build());
    }

    @Test
    void nullCheckResultBecomesSafeDownStatusRatherThanEscapingTheEndpoint() {
        var registry = HealthRegistry.builder().check(new HealthCheck() {
            @Override
            public String name() {
                return "null-result";
            }

            @Override
            public java.util.concurrent.CompletionStage<HealthStatus> check(HealthCheckContext context) {
                return CompletableFuture.completedFuture(null);
            }
        }).build();
        registry.markReady();

        var status = registry.readiness();
        assertFalse(status.isUp());
        assertEquals("null-result", status.details().get("check"));
    }

    private static HealthCheck check(String name, HealthStatus status) {
        return new HealthCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public java.util.concurrent.CompletionStage<HealthStatus> check(HealthCheckContext context) {
                return CompletableFuture.completedFuture(status);
            }
        };
    }
}
