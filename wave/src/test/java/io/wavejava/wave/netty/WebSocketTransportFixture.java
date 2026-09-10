package io.wavejava.wave.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Test-only deterministic slow-peer write fixture for WebSocket outbound-budget tests.
 *
 * <p>Selected writes remain retained with incomplete promises until the test explicitly completes
 * or fails them. It never uses timing to model a slow peer, and releases each held Netty message
 * exactly once on cleanup.</p>
 */
final class WebSocketTransportFixture extends ChannelDuplexHandler {
    private final Predicate<Object> holdPredicate;
    private final List<HeldWrite> held = new ArrayList<>();

    private WebSocketTransportFixture(Predicate<Object> holdPredicate) {
        this.holdPredicate = Objects.requireNonNull(holdPredicate, "holdPredicate");
    }

    /** Holds only writes matching {@code predicate}; all other protocol traffic continues normally. */
    static WebSocketTransportFixture hold(Predicate<Object> predicate) {
        return new WebSocketTransportFixture(predicate);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (holdPredicate.test(message)) {
            held.add(new HeldWrite(message, promise));
            return;
        }
        context.write(message, promise);
    }

    /** Returns whether at least one selected write remains physically unresolved. */
    boolean isHolding() {
        return !held.isEmpty();
    }

    /** Completes every held write successfully and releases its retained message. */
    void succeedPending() {
        finishPending(null);
    }

    /** Fails every held write with a deterministic slow-peer failure and releases its message. */
    void failPending() {
        finishPending(new IllegalStateException("test peer stopped reading"));
    }

    private void finishPending(Throwable failure) {
        var snapshot = List.copyOf(held);
        held.clear();
        for (var write : snapshot) {
            ReferenceCountUtil.release(write.message());
            if (failure == null) {
                write.promise().trySuccess();
            } else {
                write.promise().tryFailure(failure);
            }
        }
    }

    private record HeldWrite(Object message, ChannelPromise promise) {
    }
}
