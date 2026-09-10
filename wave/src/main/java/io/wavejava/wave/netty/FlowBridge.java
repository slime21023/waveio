package io.wavejava.wave.netty;

import io.netty.channel.ChannelHandlerContext;
import io.wavejava.wave.runtime.FlowSubscriptionController;
import io.wavejava.wave.runtime.OutboundByteBudget;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty-side subscriber for one HTTP response body publisher.
 *
 * <p>The bridge requests exactly one Flow item only when the channel is writable and its outbound
 * byte budget has space. It copies each emitted {@link ByteBuffer} before scheduling a Netty
 * write, retains no item after that write's completion, and cancels its subscription on any
 * connection failure, shutdown, or client disconnect.</p>
 */
final class FlowBridge implements Flow.Subscriber<ByteBuffer> {
    interface Listener {
        /** Called after one response body item has been acknowledged by the transport. */
        default void onItemWritten(int bytes) {
            // Optional accounting hook for transport observers.
        }

        /** Called on the connection event loop after the terminal HTTP chunk is written. */
        void onComplete();

        /** Called on the connection event loop when committed stream output can no longer continue. */
        void onFailure(String reason, Throwable cause);
    }

    private final ChannelHandlerContext context;
    private final PreparedResponse response;
    private final OutboundByteBudget budget;
    private final FlowSubscriptionController controller = new FlowSubscriptionController();
    private final Executor subscriptionExecutor;
    private final Listener listener;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean terminalWriteStarted = new AtomicBoolean();
    /**
     * Serializes every call into arbitrary publisher code. A virtual-thread executor may run tasks
     * concurrently, while a synchronous publisher is allowed to call this subscriber from inside
     * {@code request(1)}. Keeping these calls serial preserves one-at-a-time demand without ever
     * moving application code back onto the Netty event loop.
     */
    private final ConcurrentLinkedQueue<Runnable> upstreamActions = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean upstreamDrainScheduled = new AtomicBoolean();
    private final ThreadLocal<Boolean> upstreamActionThread = ThreadLocal.withInitial(() -> false);

    FlowBridge(
            ChannelHandlerContext context,
            PreparedResponse response,
            long maximumOutboundBytes,
            Executor subscriptionExecutor,
            Listener listener) {
        this.context = Objects.requireNonNull(context, "context");
        this.response = Objects.requireNonNull(response, "response");
        budget = new OutboundByteBudget(maximumOutboundBytes);
        this.subscriptionExecutor = Objects.requireNonNull(subscriptionExecutor, "subscriptionExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Subscribes to the response publisher away from the transport event loop. */
    void start() {
        requireEventLoop();
        if (terminal.get()) {
            return;
        }
        enqueueUpstreamAction(this::subscribePublisher);
    }

    /** Re-evaluates one-item demand after Netty reports a writability transition. */
    void onChannelWritabilityChanged() {
        requireEventLoop();
        requestNextIfPossible();
    }

    /** Cancels upstream without attempting a second response after transport cancellation. */
    void cancel() {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        budget.close();
        cancelUpstream(controller.cancel());
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        if (!controller.onSubscribe(subscription)) {
            cancelUpstream(subscription);
            return;
        }
        executeOnEventLoop(this::requestNextIfPossible);
    }

    @Override
    public void onNext(ByteBuffer item) {
        if (item == null) {
            fail("response stream emitted a null buffer", null);
            return;
        }
        if (!controller.onNext()) {
            fail("response stream emitted data without transport demand", null);
            return;
        }

        final int byteCount = item.remaining();
        if (byteCount == 0) {
            fail("response stream emitted an empty buffer", null);
            return;
        }
        var reservation = budget.tryReserve(byteCount);
        if (!reservation.accepted()) {
            fail("response stream exceeded the outbound byte budget", null);
            return;
        }

        final byte[] owned;
        try {
            owned = new byte[byteCount];
            item.slice().get(owned);
        } catch (Throwable failure) {
            budget.release(byteCount);
            fail("response stream buffer could not be copied", failure);
            return;
        }
        executeOnEventLoop(() -> writeItem(owned));
    }

    @Override
    public void onError(Throwable failure) {
        fail("response stream publisher failed", Objects.requireNonNull(failure, "failure"));
    }

    @Override
    public void onComplete() {
        if (controller.onComplete()) {
            executeOnEventLoop(this::writeTerminalChunk);
            return;
        }
        // A publisher must establish its subscription before any terminal signal. Unlike a
        // duplicate completion after UPSTREAM_COMPLETED, a completion from NEW would otherwise
        // leave committed response headers with neither a terminal chunk nor a released HTTP/1
        // sequencing slot.
        if (controller.snapshot().state() == FlowSubscriptionController.State.NEW) {
            fail("response stream publisher completed before onSubscribe", null);
        }
    }

    private void writeItem(byte[] owned) {
        requireEventLoop();
        if (terminal.get() || !context.channel().isActive()) {
            budget.release(owned.length);
            return;
        }
        try {
            ResponseWriteHandler.writeStreamChunk(context, owned)
                    .addListener(future -> onItemWriteComplete(
                            owned.length,
                            future.isSuccess(),
                            future.cause()));
        } catch (Throwable failure) {
            onItemWriteComplete(owned.length, false, failure);
        }
    }

    private void onItemWriteComplete(int bytes, boolean success, Throwable failure) {
        requireEventLoop();
        budget.release(bytes);
        if (terminal.get()) {
            return;
        }
        if (!success) {
            fail("response stream write failed", failure);
            return;
        }
        listener.onItemWritten(bytes);
        if (controller.onItemWritten()) {
            writeTerminalChunk();
        } else {
            requestNextIfPossible();
        }
    }

    private void requestNextIfPossible() {
        requireEventLoop();
        if (terminal.get()) {
            return;
        }
        var snapshot = budget.snapshot();
        var subscription = controller.requestNextIfPermitted(
                context.channel().isWritable() && context.channel().isActive(),
                !snapshot.closed() && snapshot.availableBytes() > 0);
        if (subscription == null) {
            return;
        }
        requestUpstream(subscription);
    }

    private void writeTerminalChunk() {
        requireEventLoop();
        if (terminal.get() || !terminalWriteStarted.compareAndSet(false, true)) {
            return;
        }
        if (!context.channel().isActive()) {
            fail("response stream channel became inactive", null);
            return;
        }
        try {
            ResponseWriteHandler.writeStreamEnd(context).addListener(future -> {
                if (!future.isSuccess()) {
                    fail("response stream terminal write failed", future.cause());
                    return;
                }
                if (terminal.compareAndSet(false, true)) {
                    listener.onComplete();
                }
            });
        } catch (Throwable failure) {
            fail("response stream terminal write setup failed", failure);
        }
    }

    private void fail(String reason, Throwable failure) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        budget.close();
        cancelUpstream(controller.fail());
        executeOnEventLoop(() -> listener.onFailure(reason, failure));
    }

    /** Invokes {@code subscribe} as a serialized application-side action. */
    private void subscribePublisher() {
        if (terminal.get()) {
            return;
        }
        try {
            response.stream().subscribe(this);
        } catch (Throwable failure) {
            fail("response stream subscription failed", failure);
        }
    }

    /**
     * Requests one upstream item on the application executor. The controller reserves demand on
     * the event loop before this action is queued, so a channel-writability event cannot issue a
     * duplicate request while this call is pending.
     */
    private void requestUpstream(Flow.Subscription subscription) {
        enqueueUpstreamAction(() -> {
            if (terminal.get()) {
                return;
            }
            try {
                subscription.request(1);
            } catch (Throwable failure) {
                fail("response stream publisher rejected transport demand", failure);
            }
        });
    }

    /** Runs arbitrary publisher cancellation code outside the transport event loop. */
    private void cancelUpstream(Flow.Subscription subscription) {
        if (subscription == null) {
            return;
        }
        // A synchronous publisher can emit an invalid item from inside request(1). In that case
        // cancellation must reach it before request returns (otherwise it may complete and hide
        // the cancellation); this thread is the explicitly off-loop serial lane, never Netty.
        if (upstreamActionThread.get() && !context.executor().inEventLoop()) {
            cancelSubscription(subscription);
            return;
        }
        enqueueUpstreamAction(() -> {
            cancelSubscription(subscription);
        });
    }

    /**
     * Adds an action to the per-bridge serial lane. A general executor does not guarantee FIFO or
     * single-threaded execution; this drain does, including when an onNext-triggered write queues
     * the next request while the previous request call is still on its stack.
     */
    private void enqueueUpstreamAction(Runnable action) {
        upstreamActions.add(Objects.requireNonNull(action, "action"));
        scheduleUpstreamDrain();
    }

    private void scheduleUpstreamDrain() {
        if (!upstreamDrainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            subscriptionExecutor.execute(this::drainUpstreamActions);
        } catch (RuntimeException rejection) {
            upstreamDrainScheduled.set(false);
            upstreamActions.clear();
            if (!terminal.get()) {
                fail("response stream subscription executor rejected work", rejection);
            }
        }
    }

    private void drainUpstreamActions() {
        upstreamActionThread.set(true);
        try {
            while (true) {
                Runnable action;
                while ((action = upstreamActions.poll()) != null) {
                    try {
                        action.run();
                    } catch (Throwable failure) {
                        fail("response stream application action failed", failure);
                    }
                }

                // Do not leave a race between observing the empty queue and releasing the
                // scheduled flag: an enqueuer either sees the flag and we continue below, or
                // schedules a fresh drain after we return.
                upstreamDrainScheduled.set(false);
                if (upstreamActions.isEmpty() || !upstreamDrainScheduled.compareAndSet(false, true)) {
                    return;
                }
            }
        } finally {
            upstreamActionThread.remove();
        }
    }

    private static void cancelSubscription(Flow.Subscription subscription) {
        try {
            subscription.cancel();
        } catch (RuntimeException ignored) {
            // A publisher cannot prevent transport teardown.
        }
    }

    private void executeOnEventLoop(Runnable action) {
        if (context.executor().inEventLoop()) {
            action.run();
            return;
        }
        try {
            context.executor().execute(action);
        } catch (RejectedExecutionException ignored) {
            // The channel executor is stopping; its close path owns final response cleanup.
        }
    }

    private void requireEventLoop() {
        if (!context.executor().inEventLoop()) {
            throw new IllegalStateException("Flow bridge state must be advanced on the channel event loop");
        }
    }
}
