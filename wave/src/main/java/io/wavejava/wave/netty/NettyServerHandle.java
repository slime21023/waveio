package io.wavejava.wave.netty;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.wavejava.wave.api.server.RunningServer;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.runtime.ShutdownCoordinator;
import io.wavejava.wave.runtime.server.ServerRuntime;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Internal implementation of the public running-server lifecycle. */
final class NettyServerHandle implements RunningServer {
    private final Channel channel;
    private final EventLoopGroup boss;
    private final EventLoopGroup workers;
    private final ConnectionLifecycleManager connections;
    private final Duration shutdownTimeout;
    private final ServerRuntime runtime;
    private final ShutdownCoordinator shutdown = new ShutdownCoordinator();

    NettyServerHandle(
            Channel channel,
            EventLoopGroup boss,
            EventLoopGroup workers,
            ConnectionLifecycleManager connections,
            ServerTimeouts timeouts,
            ServerRuntime runtime) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.boss = Objects.requireNonNull(boss, "boss");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.connections = Objects.requireNonNull(connections, "connections");
        shutdownTimeout = Objects.requireNonNull(timeouts, "timeouts").shutdownTimeout();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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
        RuntimeException runtimeStopFailure = null;
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
                runtime.beginShutdown();
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
                }
            }
        } finally {
            try {
                var groupTimeout = budget.remainingNanos();
                var bossShutdown = boss.shutdownGracefully(0, groupTimeout, TimeUnit.NANOSECONDS);
                interrupted |= await(bossShutdown, budget);
                if (!workersStopped) {
                    var workersShutdown = workers.shutdownGracefully(0, budget.remainingNanos(), TimeUnit.NANOSECONDS);
                    interrupted |= await(workersShutdown, budget);
                }
            } finally {
                runtimeStopFailure = runtime.finishShutdown(Duration.ofNanos(budget.remainingNanos()));
                shutdown.completeShutdown();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (runtimeStopFailure != null) {
            throw runtimeStopFailure;
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
