package io.wavejava.wave.netty;

import io.wavejava.wave.api.websocket.WebSocketMessage;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

/**
 * Package-private one-subscriber, one-retained-data-message bridge for client sessions.
 *
 * <p>All callbacks, including {@code onSubscribe}, are submitted to the client callback executor
 * so a continuation attached to {@code WebSocketClient.connect(...)} cannot accidentally run user
 * Flow code on the Netty EventLoop. A second retained data message is a bounded protocol failure;
 * Ping/Pong never enter this publisher.</p>
 */
final class WebSocketClientInboundPublisher implements Flow.Publisher<WebSocketMessage> {
    interface Listener {
        void onFailure(String reason, Throwable cause);

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

    WebSocketClientInboundPublisher(Executor callbackExecutor, Listener listener) {
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super WebSocketMessage> candidate) {
        var target = Objects.requireNonNull(candidate, "subscriber");
        synchronized (lock) {
            if (subscriber == null) {
                subscriber = target;
            } else {
                rejectSecondSubscriber(target);
                return;
            }
        }
        try {
            callbackExecutor.execute(() -> deliverSubscription(target));
        } catch (RuntimeException rejection) {
            fail("WebSocket inbound callback executor rejected onSubscribe", rejection);
        }
    }

    /** Offers an immutable application data message from the Netty session handler. */
    boolean offer(WebSocketMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.type() != WebSocketMessage.Type.TEXT && message.type() != WebSocketMessage.Type.BINARY) {
            throw new IllegalArgumentException("only WebSocket text or binary messages may enter the inbound bridge");
        }
        Drain drain;
        Failure failure = null;
        synchronized (lock) {
            if (terminal) {
                return false;
            }
            if (pendingData != null) {
                failure = failLocked("WebSocket inbound subscriber exceeded its one-message transport buffer", null);
            } else {
                pendingData = message;
            }
            drain = drainLocked();
        }
        // `offer` is called only by the session's data-frame path. A false result tells that
        // owner to send the resource-exhaustion close (1009). Do not route an overflow through
        // the generic callback-failure listener, which deliberately maps application failures
        // to 1011 and would win the close-code race on the EventLoop.
        run(drain);
        return failure == null;
    }

    /** Completes the source after a locally initiated graceful close. */
    void complete() {
        terminal(null, null);
    }

    /** Records an optional demand-respecting peer close notification then completes. */
    void completeAfterPeerClose(WebSocketMessage close) {
        Objects.requireNonNull(close, "close");
        if (close.type() != WebSocketMessage.Type.CLOSE) {
            throw new IllegalArgumentException("peer close notification must have CLOSE type");
        }
        terminal(null, close);
    }

    /** Fails the source after disconnect, cancellation, or protocol failure. */
    void fail(String reason, Throwable cause) {
        Objects.requireNonNull(reason, "reason");
        terminal(cause == null ? new IllegalStateException(reason) : cause, null);
    }

    private void deliverSubscription(Flow.Subscriber<? super WebSocketMessage> target) {
        try {
            target.onSubscribe(new Subscription());
        } catch (Throwable failure) {
            fail("WebSocket inbound subscriber rejected onSubscribe", failure);
            return;
        }
        Drain drain;
        synchronized (lock) {
            if (subscriber != target || subscriberCancelled) {
                return;
            }
            subscriptionReady = true;
            drain = drainLocked();
        }
        run(drain);
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
    }

    private void cancel() {
        synchronized (lock) {
            if (subscriberCancelled) {
                return;
            }
            subscriberCancelled = true;
            terminal = true;
            pendingData = null;
            peerClose = null;
            demand = 0;
            generation++;
            if (deliveryInFlight && !callbackInProgress) {
                deliveryInFlight = false;
            }
        }
    }

    private void terminal(Throwable failure, WebSocketMessage close) {
        Drain drain;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            terminal = true;
            terminalFailure = failure;
            // A peer close cannot wait indefinitely for a subscriber that withholds demand.
            peerClose = close != null && demand > 0 ? close : null;
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
                    case NONE -> throw new AssertionError("invalid inbound drain");
                }
            });
        } catch (RuntimeException rejection) {
            if (drain.kind().isTerminal()) {
                listener.onTerminalDeliveryRejected(rejection);
            } else {
                fail("WebSocket inbound callback executor rejected delivery", rejection);
            }
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
            return;
        }
        synchronized (lock) {
            callbackInProgress = false;
            deliveryInFlight = false;
            next = drainLocked();
        }
        run(next);
    }

    private void deliverClose(Drain drain) {
        if (!beginDelivery(drain)) {
            return;
        }
        try {
            drain.subscriber().onNext(drain.message());
        } catch (Throwable ignored) {
            // A peer-close notification cannot reopen the already closing connection.
        }
        Drain next;
        synchronized (lock) {
            callbackInProgress = false;
            deliveryInFlight = false;
            next = drainLocked();
        }
        run(next);
    }

    private boolean beginDelivery(Drain drain) {
        synchronized (lock) {
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
            // A terminal callback cannot reopen a session.
        }
    }

    private void error(Drain drain) {
        if (!beginTerminal(drain)) {
            return;
        }
        try {
            drain.subscriber().onError(drain.failure());
        } catch (Throwable ignored) {
            // A terminal callback cannot reopen a session.
        }
    }

    private boolean beginTerminal(Drain drain) {
        synchronized (lock) {
            return terminal && subscriptionReady && subscriber == drain.subscriber()
                    && generation == drain.generation() && terminalScheduled && !deliveryInFlight
                    && !callbackInProgress && !subscriberCancelled;
        }
    }

    private void rejectSecondSubscriber(Flow.Subscriber<? super WebSocketMessage> candidate) {
        try {
            callbackExecutor.execute(() -> {
                candidate.onSubscribe(RejectedSubscription.INSTANCE);
                candidate.onError(new IllegalStateException("WebSocket inbound publisher accepts one subscriber"));
            });
        } catch (RuntimeException ignored) {
            // The first subscriber/session is unaffected by a second invalid subscriber.
        }
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
            WebSocketClientInboundPublisher.this.request(demand);
        }

        @Override
        public void cancel() {
            WebSocketClientInboundPublisher.this.cancel();
        }
    }

    private enum RejectedSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long demand) {
            // The rejected subscriber immediately receives onError.
        }

        @Override
        public void cancel() {
            // Nothing was subscribed.
        }
    }
}

