package io.wavejava.wave.netty;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.wavejava.wave.RunningServer;
import io.wavejava.wave.api.lifecycle.ServiceLifecycle;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.runtime.InvocationRuntime;
import io.wavejava.wave.runtime.ObservabilityDispatcher;
import io.wavejava.wave.runtime.ShutdownCoordinator;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Internal implementation of the public running-server lifecycle. */
final class NettyRunningServer implements RunningServer {
    private final Channel channel;
    private final EventLoopGroup boss;
    private final EventLoopGroup workers;
    private final InvocationRuntime invocations;
    private final ConnectionLifecycleManager connections;
    private final Duration shutdownTimeout;
    private final ServiceLifecycle services;
    private final ObservabilityDispatcher observability;
    private final ShutdownCoordinator shutdown = new ShutdownCoordinator();

    NettyRunningServer(
            Channel channel,
            EventLoopGroup boss,
            EventLoopGroup workers,
            InvocationRuntime invocations,
            ConnectionLifecycleManager connections,
            ServerTimeouts timeouts,
            ServiceLifecycle services,
            ObservabilityDispatcher observability) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.boss = Objects.requireNonNull(boss, "boss");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.invocations = Objects.requireNonNull(invocations, "invocations");
        this.connections = Objects.requireNonNull(connections, "connections");
        shutdownTimeout = Objects.requireNonNull(timeouts, "timeouts").shutdownTimeout();
        this.services = Objects.requireNonNull(services, "services");
        this.observability = Objects.requireNonNull(observability, "observability");
    }

    @Override
    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    @Override
    public boolean isRunning() {
        return channel.isOpen() && shutdown.isRunning();
    }

    @Override
    public void close() {
        if (!shutdown.beginShutdown()) {
            return;
        }
        var interrupted = false;
        RuntimeException serviceStopFailure = null;
        var workersStopped = false;
        var budget = new ShutdownBudget(shutdownTimeout);
        try {
            connections.stopAccepting();
            try {
                // Closing the listening channel first prevents new requests from entering runtime.
                interrupted |= await(channel.close(), budget);
            } finally {
                // HTTP/1.x and unnegotiated peers cannot drain independently, but HTTP/2 can:
                // advertise GOAWAY, reject future streams, and let already-admitted streams
                // finish until the same monotonic shutdown budget expires. The invocation
                // runtime must stay accepting its existing work during that drain because Flow
                // callbacks and response preparation remain application work, never EventLoop
                // work.
                connections.closeNonHttp2Connections();
                interrupted |= await(connections.beginHttp2Drain(), budget);
                // Reject and cancel any stream that did not finish before the shared deadline,
                // while keeping the virtual-thread executor alive for transport cleanup.
                invocations.beginShutdown();
                try {
                    interrupted |= await(connections.closeActiveConnections(), budget);
                } finally {
                    // A child Channel close future may complete before its EventLoop has invoked
                    // RequestDispatchHandler.channelInactive. Let worker loops drain that
                    // callback first: it queues Flow subscription cancellation on the still-live
                    // virtual-thread runtime. Shutting the runtime first loses that cancellation
                    // behind a rejected cleanup submission.
                    var workerShutdown = workers.shutdownGracefully(
                            0, budget.remainingNanos(), TimeUnit.NANOSECONDS);
                    interrupted |= await(workerShutdown, budget);
                    workersStopped = workerShutdown.isDone();
                    invocations.finishShutdown();
                    interrupted |= awaitRuntimeTermination(invocations, budget);
                }
                var serviceStop = awaitServiceStop(services, budget);
                interrupted |= serviceStop.interrupted();
                serviceStopFailure = serviceStop.failure();
            }
        } finally {
            var groupTimeout = budget.remainingNanos();
            var bossShutdown = boss.shutdownGracefully(0, groupTimeout, TimeUnit.NANOSECONDS);
            interrupted |= await(bossShutdown, budget);
            if (!workersStopped) {
                var workersShutdown = workers.shutdownGracefully(0, budget.remainingNanos(), TimeUnit.NANOSECONDS);
                interrupted |= await(workersShutdown, budget);
            }
            observability.close(Duration.ofNanos(budget.remainingNanos()));
            shutdown.completeShutdown();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (serviceStopFailure != null) {
            throw serviceStopFailure;
        }
    }

    private static boolean await(io.netty.util.concurrent.Future<?> future, ShutdownBudget budget) {
        var interrupted = false;
        while (!future.isDone()) {
            var remaining = budget.remainingNanos();
            if (remaining == 0) {
                return interrupted;
            }
            try {
                future.await(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static boolean await(java.util.concurrent.CompletableFuture<?> completion, ShutdownBudget budget) {
        var interrupted = false;
        while (!completion.isDone()) {
            var remaining = budget.remainingNanos();
            if (remaining == 0) {
                return interrupted;
            }
            try {
                completion.get(remaining, TimeUnit.NANOSECONDS);
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            } catch (TimeoutException ignored) {
                return interrupted;
            } catch (ExecutionException ignored) {
                // A failed GOAWAY write closes that one connection. Continue shutdown under the
                // same budget; no transport failure may strand server resource release.
                break;
            }
        }
        return interrupted;
    }

    private static boolean awaitRuntimeTermination(InvocationRuntime runtime, ShutdownBudget budget) {
        var interrupted = false;
        while (!runtime.isTerminated()) {
            var remaining = budget.remainingNanos();
            if (remaining == 0) {
                return interrupted;
            }
            try {
                runtime.awaitTermination(Duration.ofNanos(remaining));
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static ServiceStopResult awaitServiceStop(ServiceLifecycle services, ShutdownBudget budget) {
        final java.util.concurrent.CompletableFuture<Void> completion;
        try {
            completion = services.stop().toCompletableFuture();
        } catch (RuntimeException failure) {
            return new ServiceStopResult(false, failure);
        }

        var interrupted = false;
        while (!completion.isDone()) {
            var remaining = budget.remainingNanos();
            if (remaining == 0) {
                return new ServiceStopResult(interrupted,
                        new IllegalStateException("Timed out stopping application services"));
            }
            try {
                completion.get(remaining, TimeUnit.NANOSECONDS);
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            } catch (TimeoutException ignored) {
                return new ServiceStopResult(interrupted,
                        new IllegalStateException("Timed out stopping application services"));
            } catch (ExecutionException failure) {
                return new ServiceStopResult(interrupted, asRuntimeFailure(failure.getCause()));
            }
        }
        if (completion.isCompletedExceptionally()) {
            try {
                completion.join();
            } catch (java.util.concurrent.CompletionException failure) {
                return new ServiceStopResult(interrupted, asRuntimeFailure(failure.getCause()));
            }
        }
        return new ServiceStopResult(interrupted, null);
    }

    private static RuntimeException asRuntimeFailure(Throwable failure) {
        return failure instanceof RuntimeException runtimeFailure
                ? runtimeFailure
                : new IllegalStateException("Application service shutdown failed", failure);
    }

    private record ServiceStopResult(boolean interrupted, RuntimeException failure) {
    }

    /** Monotonic shared time budget spanning listener, runtime, child channels, and EventLoops. */
    private static final class ShutdownBudget {
        private final long timeoutNanos;
        private final long startedAt = System.nanoTime();

        private ShutdownBudget(Duration timeout) {
            timeoutNanos = toNanosSaturated(timeout);
        }

        private long remainingNanos() {
            var elapsed = System.nanoTime() - startedAt;
            if (elapsed <= 0) {
                return timeoutNanos;
            }
            return elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
        }
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
