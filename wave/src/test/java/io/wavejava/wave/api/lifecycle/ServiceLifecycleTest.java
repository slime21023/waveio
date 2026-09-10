package io.wavejava.wave.api.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class ServiceLifecycleTest {
    @Test
    void startsDependenciesFirstAndStopsSuccessfullyStartedServicesInReverseOrder() {
        var events = new ArrayList<String>();
        var database = service("database", Set.of(), events, null, null);
        var http = service("http", Set.of("database"), events, null, null);
        var lifecycle = ServiceLifecycle.of(ServiceContext.empty(), http, database);

        lifecycle.start().toCompletableFuture().join();
        lifecycle.stop().toCompletableFuture().join();

        assertEquals(List.of("start:database", "start:http", "stop:http", "stop:database"), events);
        assertEquals(List.of("database", "http"), lifecycle.startupOrder());
        assertEquals(ServiceLifecycle.State.STOPPED, lifecycle.state());
    }

    @Test
    void startupFailureRollsBackOnlySuccessfullyStartedServicesInReverseOrder() {
        var events = new ArrayList<String>();
        var database = service("database", Set.of(), events, null, null);
        var cache = service("cache", Set.of("database"), events, new IllegalStateException("cache unavailable"), null);
        var http = service("http", Set.of("cache"), events, null, null);
        var lifecycle = ServiceLifecycle.of(ServiceContext.empty(), http, cache, database);

        var failure = assertThrows(CompletionException.class, () -> lifecycle.start().toCompletableFuture().join());
        var lifecycleFailure = assertInstanceOf(ServiceLifecycleException.class, failure.getCause());

        assertEquals(ServiceLifecycleException.Operation.START, lifecycleFailure.operation());
        assertEquals("cache", lifecycleFailure.serviceId().orElseThrow());
        assertEquals(List.of("start:database", "start:cache", "stop:database"), events);
        assertEquals(ServiceLifecycle.State.FAILED, lifecycle.state());
        assertTrue(lifecycle.stop().toCompletableFuture().isDone());
        assertEquals(List.of("start:database", "start:cache", "stop:database"), events);
    }

    @Test
    void shutdownAttemptsEveryServiceAndAggregatesFailures() {
        var events = new ArrayList<String>();
        var database = service("database", Set.of(), events, null, new IllegalStateException("database close failed"));
        var http = service("http", Set.of("database"), events, null, new IllegalStateException("http close failed"));
        var lifecycle = ServiceLifecycle.of(ServiceContext.empty(), database, http);
        lifecycle.start().toCompletableFuture().join();

        var failure = assertThrows(CompletionException.class, () -> lifecycle.stop().toCompletableFuture().join());
        var aggregate = assertInstanceOf(ServiceLifecycleException.class, failure.getCause());

        assertEquals(ServiceLifecycleException.Operation.STOP, aggregate.operation());
        assertFalse(aggregate.serviceId().isPresent());
        assertEquals(1, aggregate.getSuppressed().length);
        assertEquals(List.of("start:database", "start:http", "stop:http", "stop:database"), events);
        assertEquals(ServiceLifecycle.State.STOPPED, lifecycle.state());
    }

    @Test
    void rejectsUnknownAndCyclicDependenciesBeforeCallingAnyService() {
        var events = new ArrayList<String>();
        var unknown = service("http", Set.of("database"), events, null, null);
        var unknownFailure = assertThrows(
                ServiceConfigurationException.class,
                () -> ServiceLifecycle.of(ServiceContext.empty(), unknown));
        assertTrue(unknownFailure.getMessage().contains("unknown service 'database'"));

        var first = service("first", Set.of("second"), events, null, null);
        var second = service("second", Set.of("first"), events, null, null);
        var cycleFailure = assertThrows(
                ServiceConfigurationException.class,
                () -> ServiceLifecycle.of(ServiceContext.empty(), first, second));
        assertTrue(cycleFailure.getMessage().contains("first -> second -> first"));
        assertTrue(events.isEmpty());
    }

    @Test
    void serviceContextCarriesImmutableFrameworkConfigAndRegistry() {
        var config = io.wavejava.wave.api.config.Config.of(
                io.wavejava.wave.api.config.ConfigSource.of("defaults", java.util.Map.of("region", "tw")));
        var marker = new Object();
        var registry = io.wavejava.wave.api.registry.Registry.builder().add(Object.class, marker).build();
        var context = new ServiceContext(config, registry);
        var seen = new ServiceContext[1];
        var service = new Service() {
            @Override
            public String id() {
                return "context";
            }

            @Override
            public CompletionStage<Void> start(ServiceContext supplied) {
                seen[0] = supplied;
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }
        };

        ServiceLifecycle.of(context, service).start().toCompletableFuture().join();

        assertSame(context, seen[0]);
        assertEquals("tw", seen[0].config().require("region"));
        assertSame(marker, seen[0].registry().require(Object.class));
    }

    private static Service service(
            String id,
            Set<String> dependencies,
            List<String> events,
            Throwable startFailure,
            Throwable stopFailure
    ) {
        return new Service() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> dependencies() {
                return dependencies;
            }

            @Override
            public CompletionStage<Void> start(ServiceContext context) {
                events.add("start:" + id);
                return startFailure == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(startFailure);
            }

            @Override
            public CompletionStage<Void> stop() {
                events.add("stop:" + id);
                return stopFailure == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(stopFailure);
            }
        };
    }
}
