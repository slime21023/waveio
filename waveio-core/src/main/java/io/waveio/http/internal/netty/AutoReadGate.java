package io.waveio.http.internal.netty;

import io.netty.channel.ChannelHandlerContext;
import java.util.EnumSet;

/**
 * Owns socket-read suspension for one connection.
 *
 * <p>Reads are paused for reasons that arise and clear independently, so the transport resumes only
 * when every reason has cleared. Restoring reads because one reason ended would silently disable
 * the others — most damagingly, an unrelated pipelined response completing would cancel
 * demand-driven inbound streaming backpressure.
 */
final class AutoReadGate {
    enum Reason {
        /** The pending exchange bound is reached; further requests have nowhere ordered to go. */
        PENDING_LIMIT,
        /** An inbound request body is being read on subscriber demand. */
        INBOUND_STREAM,
        /** The connection is terminating and must never read again. */
        CLOSED
    }

    private final EnumSet<Reason> suspended = EnumSet.noneOf(Reason.class);

    void set(ChannelHandlerContext context, Reason reason, boolean active) {
        boolean changed = active ? suspended.add(reason) : suspended.remove(reason);
        if (changed) context.channel().config().setAutoRead(suspended.isEmpty());
    }
}
