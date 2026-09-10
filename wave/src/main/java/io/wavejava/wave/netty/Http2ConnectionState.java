package io.wavejava.wave.netty;

import io.wavejava.wave.api.http.Http2Config;
import io.wavejava.wave.api.server.ServerLimits;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** Per-parent-connection finite admission and memory partition for HTTP/2 stream children. */
final class Http2ConnectionState {
    private final Http2Config config;
    private final Semaphore streamAdmissions;
    private final int maximumAggregateRequestBodyBytes;
    private final long outboundBytesPerStream;
    // Access is confined to the parent HTTP/2 EventLoop. The completion future is intentionally
    // thread-safe because the server shutdown coordinator waits for it from its caller thread.
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private boolean draining;
    private int admittedStreams;

    Http2ConnectionState(Http2Config config, ServerLimits serverLimits) {
        this.config = Objects.requireNonNull(config, "config");
        var limits = Objects.requireNonNull(serverLimits, "serverLimits");
        streamAdmissions = new Semaphore(config.maximumConcurrentStreams(), true);
        var inboundPartition = config.maximumInboundBytesPerConnection() / config.maximumConcurrentStreams();
        maximumAggregateRequestBodyBytes = (int) Math.min(
                limits.maximumRequestBodyBytes(), Math.max(1L, Math.min(Integer.MAX_VALUE, inboundPartition)));
        // Static equal partitions deliberately trade unused capacity for a proof that all active
        // Flow bridges together remain under the connection cap without a hidden shared queue.
        outboundBytesPerStream = Math.max(1L, Math.min(
                Math.min(config.maximumOutboundBytesPerStream(), config.maximumFrameBytes()),
                config.maximumOutboundBytesPerConnection() / config.maximumConcurrentStreams()));
    }

    boolean tryAcquireStream() {
        if (draining || !streamAdmissions.tryAcquire()) {
            return false;
        }
        admittedStreams++;
        return true;
    }

    void releaseStream() {
        streamAdmissions.release();
        if (admittedStreams > 0) {
            admittedStreams--;
        }
        completeDrainIfIdle();
    }

    /** Stops new stream admission and completes once every admitted stream has reached a terminal state. */
    CompletableFuture<Void> beginDrain() {
        draining = true;
        completeDrainIfIdle();
        return drained;
    }

    /** Unblocks shutdown if the parent connection disappears before all child callbacks arrive. */
    void connectionClosed() {
        draining = true;
        drained.complete(null);
    }

    private void completeDrainIfIdle() {
        if (draining && admittedStreams == 0) {
            drained.complete(null);
        }
    }

    /**
     * Returns the static per-stream inbound cap shared by aggregate and Flow body modes.
     *
     * <p>The conservative partition keeps the total declared H2 body admission below the
     * connection budget even when every negotiated stream is active at once. Flow retains only
     * one frame at a time, but it uses the same cap so a slow or malicious peer cannot turn the
     * connection-level limit into an unbounded logical upload allowance.</p>
     */
    int maximumAggregateRequestBodyBytes() {
        return maximumAggregateRequestBodyBytes;
    }

    long outboundBytesPerStream() {
        return outboundBytesPerStream;
    }

    Http2Config config() {
        return config;
    }
}
