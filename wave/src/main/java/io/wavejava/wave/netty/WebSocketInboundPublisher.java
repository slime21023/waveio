package io.wavejava.wave.netty;

import io.wavejava.wave.api.websocket.WebSocketMessage;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

/**
 * One-subscriber, one-retained-data-message bridge between a Netty WebSocket pipeline and
 * application Flow callbacks.
 *
 * <p>All subscriber callbacks are submitted to the supplied invocation executor. The transport
 * retains at most one application data message while a subscriber is slow or has not yet
 * requested demand; a second retained data message is a deterministic session failure instead of
 * an unbounded queue. A peer close is an optional separately bounded terminal notification: when
 * demand remains, it emits {@code CLOSE}, then emits {@code onComplete}; otherwise it drops that
 * application notification and completes immediately. That keeps WebSocket close progress
 * independent from a slow data subscriber without violating Flow demand.</p>
 */
final class WebSocketInboundPublisher implements Flow.Publisher<WebSocketMessage> {
    interface Listener {
        /** The publisher's one-message transport capacity changed. */
        void onReadCapacityChanged();

        /** A subscriber protocol or callback failure requires session shutdown. */
        void onFailure(String reason, Throwable cause);

        /** The executor could not deliver a promised terminal callback. */
        void onTerminalDeliveryRejected(Throwable cause);
    }

    private final Object lock = new Object();
    private final Executor callbackExecutor;
    private final Listener listener;

    private Flow.Subscriber<? super WebSocketMessage> subscriber;
    private WebSocketMessage pendingData;
    private WebSocketMessage peerClose;
    private long demand;
    private boolean subscriptionReady;
    private boolean deliveryInFlight;
    private boolean callbackInProgress;
    private boolean terminal;
    private boolean subscriberCancelled;
    private Throwable terminalFailure;
    private boolean terminalScheduled;
    private long generation;

    WebSocketInboundPublisher(Executor callbackExecutor, Listener listener) {
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super WebSocketMessage> candidate) {
        var target = Objects.requireNonNull(candidate, "subscriber");
        var rejected = false;
        synchronized (lock) {
            if (subscriber != null) {
                rejected = true;
            } else {
                subscriber = target;
            }
        }
        if (rejected) {
            // User callbacks must never run while the publisher's state lock is held: a callback
            // may synchronously request, cancel, or inspect session state.
            rejectSecondSubscriber(target);
            return;
        }
        try {
            target.onSubscribe(new Subscription());
        } catch (Throwable failure) {
            fail("WebSocket inbound subscriber rejected onSubscribe", failure);
            return;
        }
        Drain drain;
        synchronized (lock) {
            subscriptionReady = true;
            drain = drainLocked();
        }
        run(drain);
        listener.onReadCapacityChanged();
    }

    /** Offers a framework-owned immutable application data message from the connection event loop. */
    boolean offer(WebSocketMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.type() != WebSocketMessage.Type.TEXT && message.type() != WebSocketMessage.Type.BINARY) {
            throw new IllegalArgumentException("only WebSocket application data may enter the inbound data bridge");
        }
        Drain drain;
        Failure failure = null;
        synchronized (lock) {
            if (terminal) {
                return false;
            }
            if (pendingData != null) {
                failure = failLocked("WebSocket inbound subscriber exceeded its one-message transport buffer", null);
                drain = drainLocked();
            } else {
                pendingData = message;
                drain = drainLocked();
            }
        }
        if (failure != null) {
            listener.onFailure(failure.reason(), failure.cause());
        }
        run(drain);
        listener.onReadCapacityChanged();
        return failure == null;
    }

    /**
     * Completes the inbound source after a locally initiated graceful close.
     *
     * <p>A data callback already admitted to the executor is allowed to finish before the terminal
     * signal; data still retained without demand is discarded because the transport has closed.</p>
     */
    void complete() {
        terminal(null, null);
    }

    /**
     * Records the peer's close notification. It is the only control message exposed by
     * {@code WebSocketSession.inbound()}. It is delivered only when demand remains at peer-close
     * time; otherwise this source completes immediately without a CLOSE item.
     */
    void completeAfterPeerClose(WebSocketMessage close) {
        Objects.requireNonNull(close, "close");
        if (close.type() != WebSocketMessage.Type.CLOSE) {
            throw new IllegalArgumentException("peer close notification must have CLOSE type");
        }
        terminal(null, close);
    }

    /** Fails the inbound source after disconnect, shutdown, or protocol failure. */
    void fail(String reason, Throwable cause) {
        Objects.requireNonNull(reason, "reason");
        terminal(cause == null ? new IllegalStateException(reason) : cause, null);
    }

    /** Returns whether the Netty pipeline may accept one more complete inbound data message. */
    boolean canAcceptInput() {
        synchronized (lock) {
            return !terminal && pendingData == null;
        }
    }

    private void request(long requested) {
        Drain drain;
        Failure failure = null;
        synchronized (lock) {
            if (subscriberCancelled || (terminal && peerClose == null)) {
                return;
            }
            if (requested <= 0) {
                failure = failLocked("WebSocket inbound subscriber requested non-positive demand", null);
            } else {
                demand = saturatingAdd(demand, requested);
            }
            drain = drainLocked();
        }
        if (failure != null) {
            listener.onFailure(failure.reason(), failure.cause());
        }
        run(drain);
        listener.onReadCapacityChanged();
    }

    private void cancel() {
        synchronized (lock) {
            if (terminal && subscriberCancelled) {
                return;
            }
            terminal = true;
            subscriberCancelled = true;
            pendingData = null;
            peerClose = null;
            demand = 0;
            generation++;
            if (deliveryInFlight && !callbackInProgress) {
                deliveryInFlight = false;
            }
        }
        listener.onReadCapacityChanged();
    }

    /**
     * Enters a terminal state. Failure invalidates a queued callback; graceful completion keeps a
     * callback that was already admitted to the executor so Flow ordering remains intact.
     */
    private void terminal(Throwable failure, WebSocketMessage close) {
        Drain drain;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            terminal = true;
            terminalFailure = failure;
            // A peer close never waits for future application demand: close the Flow immediately
            // unless the subscriber already has capacity for this optional terminal item.
            peerClose = close != null && demand > 0 ? close : null;
            // An unadmitted data message has no matching demand and must not hold up close. A
            // delivery already in flight has consumed demand and is allowed to finish in order.
            pendingData = null;
            if (failure != null) {
                demand = 0;
                generation++;
                if (deliveryInFlight && !callbackInProgress) {
                    deliveryInFlight = false;
                }
            }
            drain = drainLocked();
        }
        run(drain);
        listener.onReadCapacityChanged();
    }

    private Failure failLocked(String reason, Throwable cause) {
        if (terminal) {
            return null;
        }
        terminal = true;
        terminalFailure = cause == null ? new IllegalStateException(reason) : cause;
        pendingData = null;
        peerClose = null;
        demand = 0;
        generation++;
        if (deliveryInFlight && !callbackInProgress) {
            deliveryInFlight = false;
        }
        return new Failure(reason, terminalFailure);
    }

    private Drain drainLocked() {
        if (subscriber == null || !subscriptionReady || deliveryInFlight || terminalScheduled || subscriberCancelled) {
            return Drain.none();
        }
        if (!terminal && pendingData != null && demand > 0) {
            var next = pendingData;
            pendingData = null;
            demand--;
            deliveryInFlight = true;
            return Drain.data(subscriber, next, generation);
        }
        if (terminal && peerClose != null && demand > 0) {
            var close = peerClose;
            peerClose = null;
            demand--;
            deliveryInFlight = true;
            return Drain.close(subscriber, close, generation);
        }
        if (terminal && peerClose == null) {
            terminalScheduled = true;
            return terminalFailure == null
                    ? Drain.complete(subscriber, generation)
                    : Drain.error(subscriber, terminalFailure, generation);
        }
        return Drain.none();
    }

    private void run(Drain drain) {
        if (drain.kind() == DrainKind.NONE) {
            return;
        }
        try {
            callbackExecutor.execute(() -> {
                switch (drain.kind()) {
                    case DATA -> deliverData(drain);
                    case CLOSE -> deliverClose(drain);
                    case COMPLETE -> complete(drain);
                    case ERROR -> error(drain);
                    case NONE -> throw new AssertionError("invalid WebSocket inbound drain");
                }
            });
        } catch (RuntimeException rejection) {
            if (drain.kind().isTerminal()) {
                listener.onTerminalDeliveryRejected(rejection);
                return;
            }
            fail("WebSocket inbound callback executor rejected delivery", rejection);
        }
    }

    private void deliverData(Drain drain) {
        if (!beginDelivery(drain)) {
            return;
        }
        Failure failure = null;
        Drain next;
        try {
            drain.subscriber().onNext(drain.message());
        } catch (Throwable callbackFailure) {
            synchronized (lock) {
                callbackInProgress = false;
                deliveryInFlight = false;
                if (!terminal) {
                    failure = failLocked("WebSocket inbound subscriber failed", callbackFailure);
                }
                next = drainLocked();
            }
            if (failure != null) {
                listener.onFailure(failure.reason(), failure.cause());
            }
            run(next);
            listener.onReadCapacityChanged();
            return;
        }
        synchronized (lock) {
            callbackInProgress = false;
            deliveryInFlight = false;
            next = drainLocked();
        }
        run(next);
        listener.onReadCapacityChanged();
    }

    private void deliverClose(Drain drain) {
        if (!beginDelivery(drain)) {
            return;
        }
        Drain next;
        try {
            drain.subscriber().onNext(drain.message());
        } catch (Throwable ignored) {
            // A peer-close notification is terminal transport state. The connection has already
            // begun its wire close handshake, so an application callback cannot reopen it.
        }
        synchronized (lock) {
            callbackInProgress = false;
            deliveryInFlight = false;
            next = drainLocked();
        }
        run(next);
        listener.onReadCapacityChanged();
    }

    private boolean beginDelivery(Drain drain) {
        synchronized (lock) {
            // A graceful terminal transition intentionally preserves a data callback that was
            // already admitted to the executor. Failure/cancellation increments generation and
            // suppresses every stale callback before it can reach user code.
            if (subscriberCancelled || !subscriptionReady || subscriber != drain.subscriber()
                    || generation != drain.generation() || !deliveryInFlight || callbackInProgress || terminalScheduled) {
                return false;
            }
            callbackInProgress = true;
            return true;
        }
    }

    private void complete(Drain drain) {
        if (!beginTerminal(drain)) {
            return;
        }
        try {
            drain.subscriber().onComplete();
        } catch (Throwable ignored) {
            // A terminal callback cannot reopen a closed session.
        }
    }

    private void error(Drain drain) {
        if (!beginTerminal(drain)) {
            return;
        }
        try {
            drain.subscriber().onError(drain.failure());
        } catch (Throwable ignored) {
            // A terminal callback cannot reopen a closed session.
        }
    }

    private boolean beginTerminal(Drain drain) {
        synchronized (lock) {
            return terminal && subscriptionReady && subscriber == drain.subscriber()
                    && generation == drain.generation() && terminalScheduled && !deliveryInFlight
                    && !callbackInProgress && !subscriberCancelled;
        }
    }

    private static void rejectSecondSubscriber(Flow.Subscriber<? super WebSocketMessage> candidate) {
        candidate.onSubscribe(RejectedSubscription.INSTANCE);
        candidate.onError(new IllegalStateException("WebSocket inbound publisher accepts one subscriber"));
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private enum DrainKind {
        NONE,
        DATA,
        CLOSE,
        COMPLETE,
        ERROR;

        boolean isTerminal() {
            return this == CLOSE || this == COMPLETE || this == ERROR;
        }
    }

    private record Drain(
            DrainKind kind,
            Flow.Subscriber<? super WebSocketMessage> subscriber,
            WebSocketMessage message,
            Throwable failure,
            long generation) {
        static Drain none() {
            return new Drain(DrainKind.NONE, null, null, null, 0);
        }

        static Drain data(Flow.Subscriber<? super WebSocketMessage> subscriber, WebSocketMessage message, long generation) {
            return new Drain(DrainKind.DATA, subscriber, message, null, generation);
        }

        static Drain close(Flow.Subscriber<? super WebSocketMessage> subscriber, WebSocketMessage message, long generation) {
            return new Drain(DrainKind.CLOSE, subscriber, message, null, generation);
        }

        static Drain complete(Flow.Subscriber<? super WebSocketMessage> subscriber, long generation) {
            return new Drain(DrainKind.COMPLETE, subscriber, null, null, generation);
        }

        static Drain error(Flow.Subscriber<? super WebSocketMessage> subscriber, Throwable failure, long generation) {
            return new Drain(DrainKind.ERROR, subscriber, null, failure, generation);
        }
    }

    private record Failure(String reason, Throwable cause) {
    }

    private final class Subscription implements Flow.Subscription {
        @Override
        public void request(long demand) {
            WebSocketInboundPublisher.this.request(demand);
        }

        @Override
        public void cancel() {
            WebSocketInboundPublisher.this.cancel();
        }
    }

    private enum RejectedSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long demand) {
            // The rejected subscriber receives onError immediately.
        }

        @Override
        public void cancel() {
            // Nothing was subscribed upstream.
        }
    }
}
