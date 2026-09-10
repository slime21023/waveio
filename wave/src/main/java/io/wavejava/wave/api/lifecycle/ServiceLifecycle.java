package io.wavejava.wave.api.lifecycle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Coordinates a validated graph of asynchronous framework services.
 *
 * <p>The graph is validated before startup. Startup is deterministic and sequential in dependency
 * order. If a service fails, successfully started services are stopped in reverse order. A normal
 * shutdown uses the same reverse order, attempts every started service, and exposes all failures
 * through suppressed exceptions. A lifecycle instance is one-shot: it cannot be restarted after
 * it has failed or stopped.</p>
 */
public final class ServiceLifecycle implements AutoCloseable {
    /** Observable state of this one-shot lifecycle. */
    public enum State {
        NEW,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
        FAILED
    }

    private final Object monitor = new Object();
    private final ServiceContext context;
    private final List<Service> startupOrder;
    private final List<String> startupOrderIds;

    private State state = State.NEW;
    private List<Service> startedServices = List.of();
    private CompletableFuture<Void> startResult;
    private CompletableFuture<Void> stopResult;

    private ServiceLifecycle(ServiceContext context, List<Service> startupOrder) {
        this.context = Objects.requireNonNull(context, "context");
        this.startupOrder = List.copyOf(startupOrder);
        this.startupOrderIds = startupOrder.stream().map(Service::id).toList();
    }

    /** Starts a builder for a lifecycle graph. */
    public static Builder builder() {
        return new Builder();
    }

    /** Creates and validates a lifecycle graph using {@code context}. */
    public static ServiceLifecycle of(ServiceContext context, Service... services) {
        Objects.requireNonNull(services, "services");
        var builder = builder().context(context);
        for (var service : services) {
            builder.add(service);
        }
        return builder.build();
    }

    /** Returns the current state. */
    public State state() {
        synchronized (monitor) {
            return state;
        }
    }

    /** Returns services in deterministic dependency-aware startup order. */
    public List<Service> services() {
        return startupOrder;
    }

    /** Returns service IDs in deterministic dependency-aware startup order. */
    public List<String> startupOrder() {
        return startupOrderIds;
    }

    /** Starts every service in dependency order. Repeated calls share the same result. */
    public CompletionStage<Void> start() {
        CompletableFuture<Void> resultToStart = null;
        synchronized (monitor) {
            switch (state) {
                case NEW -> {
                    state = State.STARTING;
                    startResult = new CompletableFuture<>();
                    resultToStart = startResult;
                }
                case STARTING, STARTED -> {
                    return startResult;
                }
                case STOPPING, STOPPED, FAILED -> {
                    return failedStage(new IllegalStateException("Service lifecycle cannot start from state " + state));
                }
            }
        }
        beginStart(resultToStart);
        return resultToStart;
    }

    /**
     * Stops all successfully started services in reverse startup order.
     *
     * <p>When called while startup is in progress, shutdown waits for the startup result. A
     * failed startup has already attempted rollback, so its subsequent stop operation completes
     * without invoking services a second time.</p>
     */
    public CompletionStage<Void> stop() {
        CompletableFuture<Void> startToAwait = null;
        CompletableFuture<Void> resultToStop = null;
        List<Service> servicesToStop = null;
        synchronized (monitor) {
            switch (state) {
                case NEW -> {
                    state = State.STOPPED;
                    stopResult = CompletableFuture.completedFuture(null);
                    return stopResult;
                }
                case STARTING -> {
                    startToAwait = startResult;
                }
                case STARTED -> {
                    state = State.STOPPING;
                    stopResult = new CompletableFuture<>();
                    resultToStop = stopResult;
                    servicesToStop = startedServices;
                }
                case STOPPING, STOPPED -> {
                    return stopResult;
                }
                case FAILED -> {
                    if (stopResult == null) {
                        stopResult = CompletableFuture.completedFuture(null);
                    }
                    return stopResult;
                }
            }
        }
        if (startToAwait != null) {
            return startToAwait.handle((ignored, failure) -> null).thenCompose(ignored -> stop());
        }
        beginStop(resultToStop, servicesToStop);
        return resultToStop;
    }

    /**
     * Stops the lifecycle synchronously for use with try-with-resources.
     *
     * @throws ServiceLifecycleException when an asynchronous stop operation failed
     */
    @Override
    public void close() {
        try {
            stop().toCompletableFuture().join();
        } catch (CompletionException failure) {
            var cause = unwrap(failure);
            if (cause instanceof ServiceLifecycleException lifecycleFailure) {
                throw lifecycleFailure;
            }
            throw ServiceLifecycleException.aggregateStop(cause);
        }
    }

    private void beginStart(CompletableFuture<Void> result) {
        var started = new ArrayList<Service>();
        CompletionStage<Void> sequence = CompletableFuture.completedFuture(null);
        for (var service : startupOrder) {
            sequence = sequence.thenCompose(ignored -> startService(service).thenRun(() -> started.add(service)));
        }
        sequence.whenComplete((ignored, failure) -> {
            if (failure == null) {
                completeStart(result, started);
                return;
            }
            rollbackAfterStartFailure(result, started, unwrap(failure));
        });
    }

    private void completeStart(CompletableFuture<Void> result, List<Service> started) {
        synchronized (monitor) {
            startedServices = List.copyOf(started);
            state = State.STARTED;
        }
        result.complete(null);
    }

    private void rollbackAfterStartFailure(CompletableFuture<Void> result, List<Service> started, Throwable startupFailure) {
        stopServices(started).whenComplete((rollbackFailures, unexpectedFailure) -> {
            var failure = startupFailure;
            if (unexpectedFailure != null) {
                failure.addSuppressed(unwrap(unexpectedFailure));
            }
            if (rollbackFailures != null) {
                rollbackFailures.forEach(failure::addSuppressed);
            }
            synchronized (monitor) {
                startedServices = List.of();
                state = State.FAILED;
            }
            result.completeExceptionally(failure);
        });
    }

    private void beginStop(CompletableFuture<Void> result, List<Service> servicesToStop) {
        stopServices(servicesToStop).whenComplete((failures, unexpectedFailure) -> {
            var allFailures = new ArrayList<Throwable>();
            if (failures != null) {
                allFailures.addAll(failures);
            }
            if (unexpectedFailure != null) {
                allFailures.add(unwrap(unexpectedFailure));
            }
            synchronized (monitor) {
                startedServices = List.of();
                state = State.STOPPED;
            }
            if (allFailures.isEmpty()) {
                result.complete(null);
                return;
            }
            var aggregate = ServiceLifecycleException.aggregateStop(allFailures.getFirst());
            allFailures.stream().skip(1).forEach(aggregate::addSuppressed);
            result.completeExceptionally(aggregate);
        });
    }

    private CompletionStage<List<Throwable>> stopServices(List<Service> servicesToStop) {
        var reverse = new ArrayList<>(servicesToStop);
        Collections.reverse(reverse);
        CompletionStage<List<Throwable>> sequence = CompletableFuture.completedFuture(new ArrayList<>());
        for (var service : reverse) {
            sequence = sequence.thenCompose(failures -> stopService(service)
                    .handle((ignored, failure) -> {
                        if (failure != null) {
                            failures.add(unwrap(failure));
                        }
                        return failures;
                    }));
        }
        return sequence.thenApply(List::copyOf);
    }

    private CompletionStage<Void> startService(Service service) {
        try {
            var stage = Objects.requireNonNull(service.start(context), "Service '" + service.id() + "' returned a null start stage");
            return stage.handle((ignored, failure) -> {
                if (failure != null) {
                    throw new CompletionException(ServiceLifecycleException.start(service.id(), unwrap(failure)));
                }
                return null;
            });
        } catch (Throwable failure) {
            return failedStage(ServiceLifecycleException.start(service.id(), failure));
        }
    }

    private CompletionStage<Void> stopService(Service service) {
        try {
            var stage = Objects.requireNonNull(service.stop(), "Service '" + service.id() + "' returned a null stop stage");
            return stage.handle((ignored, failure) -> {
                if (failure != null) {
                    throw new CompletionException(ServiceLifecycleException.stop(service.id(), unwrap(failure)));
                }
                return null;
            });
        } catch (Throwable failure) {
            return failedStage(ServiceLifecycleException.stop(service.id(), failure));
        }
    }

    private static <T> CompletionStage<T> failedStage(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Builder that validates service identities and dependency relationships before startup. */
    public static final class Builder {
        private ServiceContext context = ServiceContext.empty();
        private final List<Service> services = new ArrayList<>();

        private Builder() {
        }

        /** Supplies the immutable context passed to every service startup call. */
        public Builder context(ServiceContext context) {
            this.context = Objects.requireNonNull(context, "context");
            return this;
        }

        /** Adds one service to the graph. Registration order breaks otherwise independent ties. */
        public Builder add(Service service) {
            services.add(Objects.requireNonNull(service, "service"));
            return this;
        }

        /** Adds each service in iteration order. */
        public Builder addAll(Iterable<? extends Service> services) {
            Objects.requireNonNull(services, "services");
            for (var service : services) {
                add(service);
            }
            return this;
        }

        /** Validates the graph and produces a one-shot lifecycle coordinator. */
        public ServiceLifecycle build() {
            return new ServiceLifecycle(context, resolveStartupOrder(services));
        }

        private static List<Service> resolveStartupOrder(List<Service> services) {
            var byId = new LinkedHashMap<String, Service>();
            var dependencies = new LinkedHashMap<String, List<String>>();
            for (var service : services) {
                var id = requireServiceId(service.id());
                if (byId.putIfAbsent(id, service) != null) {
                    throw new ServiceConfigurationException("Duplicate service ID '" + id + "'");
                }
                var declaredDependencies = Objects.requireNonNull(service.dependencies(), "Service '" + id + "' returned null dependencies");
                var copy = new LinkedHashSet<String>();
                for (var dependency : declaredDependencies) {
                    copy.add(requireServiceId(dependency));
                }
                dependencies.put(id, copy.stream().sorted(Comparator.naturalOrder()).toList());
            }
            for (var entry : dependencies.entrySet()) {
                for (var dependency : entry.getValue()) {
                    if (!byId.containsKey(dependency)) {
                        throw new ServiceConfigurationException(
                                "Service '" + entry.getKey() + "' depends on unknown service '" + dependency + "'");
                    }
                }
            }

            var visitState = new LinkedHashMap<String, VisitState>();
            var order = new ArrayList<Service>();
            var path = new ArrayDeque<String>();
            for (var id : byId.keySet()) {
                visit(id, byId, dependencies, visitState, path, order);
            }
            return List.copyOf(order);
        }

        private static void visit(
                String id,
                Map<String, Service> byId,
                Map<String, List<String>> dependencies,
                Map<String, VisitState> visitState,
                Deque<String> path,
                List<Service> order
        ) {
            var state = visitState.get(id);
            if (state == VisitState.DONE) {
                return;
            }
            if (state == VisitState.VISITING) {
                var cycle = new ArrayList<String>();
                var include = false;
                for (var item : path) {
                    if (item.equals(id)) {
                        include = true;
                    }
                    if (include) {
                        cycle.add(item);
                    }
                }
                cycle.add(id);
                throw new ServiceConfigurationException("Service dependency cycle: " + String.join(" -> ", cycle));
            }
            visitState.put(id, VisitState.VISITING);
            path.addLast(id);
            for (var dependency : dependencies.get(id)) {
                visit(dependency, byId, dependencies, visitState, path, order);
            }
            path.removeLast();
            visitState.put(id, VisitState.DONE);
            order.add(byId.get(id));
        }

        private static String requireServiceId(String id) {
            Objects.requireNonNull(id, "service ID");
            if (id.isBlank()) {
                throw new ServiceConfigurationException("Service ID must not be blank");
            }
            return id;
        }
    }

    private enum VisitState {
        VISITING,
        DONE
    }
}
