package io.wavejava.wave.netty;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http2.DefaultHttp2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal ownership registry for accepted transport connections.
 *
 * <p>It is intentionally separate from the connection-count limit: the limit answers whether a
 * new channel may be admitted, while this type owns every admitted channel for bounded server
 * shutdown. {@link ChannelGroup} removes closed channels automatically, so this registry never
 * grows after a peer disconnects.</p>
 */
final class ConnectionLifecycleManager {
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final ChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final Map<Channel, Http2ConnectionState> http2Connections = new ConcurrentHashMap<>();

    /** Registers a just-created channel unless shutdown has already begun. */
    boolean register(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        if (!accepting.get()) {
            return false;
        }
        connections.add(channel);
        if (accepting.get()) {
            return true;
        }
        connections.remove(channel);
        return false;
    }

    /** Prevents further connection registration before listener teardown starts. */
    void stopAccepting() {
        accepting.set(false);
    }

    /**
     * Registers the state of a negotiated HTTP/2 parent after its frame codec is installed.
     *
     * <p>A connection is first registered through {@link #register(Channel)} while it is still
     * protocol-agnostic. Keeping this second registration separate lets shutdown immediately
     * close HTTP/1.x and unnegotiated TLS peers while it performs the HTTP/2 GOAWAY drain.</p>
     */
    void registerHttp2(Channel channel, Http2ConnectionState state) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(state, "state");
        if (!channel.isActive()) {
            state.connectionClosed();
            return;
        }
        http2Connections.put(channel, state);
        channel.closeFuture().addListener(ignored -> {
            http2Connections.remove(channel, state);
            state.connectionClosed();
        });
    }

    /** Closes all accepted connections that never negotiated HTTP/2. */
    void closeNonHttp2Connections() {
        for (var connection : connections) {
            if (!http2Connections.containsKey(connection)) {
                connection.close();
            }
        }
    }

    /**
     * Sends a no-error GOAWAY on every negotiated parent, rejects future stream admission, and
     * completes once each parent has no framework-admitted stream left.
     *
     * <p>The frame codec computes the last peer-created stream ID when it serializes a GOAWAY;
     * callers must not manufacture a last-stream ID themselves. A write failure closes only that
     * parent and still completes this drain phase so the shared shutdown deadline governs the
     * remaining live parents rather than becoming an unbounded wait.</p>
     */
    CompletableFuture<Void> beginHttp2Drain() {
        var drains = new ArrayList<CompletableFuture<Void>>();
        for (var entry : http2Connections.entrySet()) {
            var channel = entry.getKey();
            var state = entry.getValue();
            var drain = new CompletableFuture<Void>();
            drains.add(drain);
            try {
                channel.eventLoop().execute(() -> {
                    if (!channel.isActive()) {
                        state.connectionClosed();
                        drain.complete(null);
                        return;
                    }
                    var streamsDrained = state.beginDrain();
                    channel.writeAndFlush(new DefaultHttp2GoAwayFrame(Http2Error.NO_ERROR)).addListener(written -> {
                        if (!written.isSuccess()) {
                            channel.close();
                            state.connectionClosed();
                            drain.complete(null);
                            return;
                        }
                        streamsDrained.whenComplete((ignored, failure) -> drain.complete(null));
                    });
                });
            } catch (RuntimeException rejected) {
                state.connectionClosed();
                drain.complete(null);
            }
        }
        return CompletableFuture.allOf(drains.toArray(CompletableFuture[]::new));
    }

    /** Closes every currently registered child channel. */
    ChannelGroupFuture closeActiveConnections() {
        return connections.close();
    }
}
