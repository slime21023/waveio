package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.wavejava.wave.api.http.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Deterministic slow-peer coverage: an incomplete Netty write must stop further Flow demand. */
class FlowBridgeSlowConsumerTest {
    @Test
    void rejectsCompletionBeforeSubscriptionInsteadOfLeavingTheHttpResponseSlotOpen() {
        var contextCapture = new ContextCapture();
        Flow.Publisher<ByteBuffer> invalidPublisher = target -> target.onComplete();
        var response = Response.create().stream(invalidPublisher);
        var prepared = PreparedResponse.render(response, HttpVersion.HTTP_1_1, "GET", true);
        var terminal = new AtomicBoolean();
        var failure = new AtomicReference<String>();

        var channel = new EmbeddedChannel(contextCapture);
        try {
            ResponseWriteHandler.writeStreamHeaders(contextCapture.context, prepared).syncUninterruptibly();
            var bridge = new FlowBridge(
                    contextCapture.context,
                    prepared,
                    32,
                    Runnable::run,
                    new FlowBridge.Listener() {
                        @Override
                        public void onComplete() {
                            terminal.set(true);
                        }

                        @Override
                        public void onFailure(String reason, Throwable cause) {
                            failure.set(reason);
                        }
                    });

            bridge.start();

            assertEquals("response stream publisher completed before onSubscribe", failure.get());
            assertFalse(terminal.get(), "an invalid publisher must not write a terminal success chunk");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void retainsOneBudgetedItemAndDoesNotDemandAnotherWhileThePeerIsSlow() throws Exception {
        var heldWrites = new HeldContentWrites();
        var contextCapture = new ContextCapture();
        var publisher = new DemandProbe();
        var response = Response.create().stream(publisher);
        var prepared = PreparedResponse.render(response, HttpVersion.HTTP_1_1, "GET", true);
        var terminal = new AtomicBoolean();

        var channel = new EmbeddedChannel(heldWrites, contextCapture);
        try {
            ResponseWriteHandler.writeStreamHeaders(contextCapture.context, prepared).syncUninterruptibly();
            var bridge = new FlowBridge(
                    contextCapture.context,
                    prepared,
                    32,
                    Runnable::run,
                    new FlowBridge.Listener() {
                        @Override
                        public void onComplete() {
                            terminal.set(true);
                        }

                        @Override
                        public void onFailure(String reason, Throwable cause) {
                            throw new AssertionError(reason, cause);
                        }
                    });

            bridge.start();

            assertTrue(heldWrites.firstContent.await(2, TimeUnit.SECONDS), "the first item should be pending at Netty");
            assertEquals(1, publisher.requestCalls.get(), "only one item may be requested before its write completes");
            assertEquals(1, publisher.largestDemand.get());
            assertTrue(!terminal.get());

            bridge.cancel();
            assertTrue(publisher.cancelled.get(), "teardown must cancel a publisher blocked by a slow peer");
        } finally {
            heldWrites.releasePending();
            channel.finishAndReleaseAll();
        }
    }

    private static final class ContextCapture extends ChannelDuplexHandler {
        private ChannelHandlerContext context;

        @Override
        public void handlerAdded(ChannelHandlerContext context) {
            this.context = context;
        }
    }

    /** Stops the first HttpContent promise, simulating a peer whose socket cannot accept bytes. */
    private static final class HeldContentWrites extends ChannelDuplexHandler {
        private final CountDownLatch firstContent = new CountDownLatch(1);
        private Object heldMessage;
        private ChannelPromise heldPromise;

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (message instanceof HttpContent && heldPromise == null) {
                heldMessage = message;
                heldPromise = promise;
                firstContent.countDown();
                return;
            }
            context.write(message, promise);
        }

        void releasePending() {
            if (heldMessage != null) {
                ReferenceCountUtil.release(heldMessage);
                heldMessage = null;
            }
            if (heldPromise != null) {
                heldPromise.tryFailure(new IllegalStateException("test peer closed"));
                heldPromise = null;
            }
        }
    }

    private static final class DemandProbe implements Flow.Publisher<ByteBuffer> {
        private final AtomicInteger requestCalls = new AtomicInteger();
        private final AtomicInteger largestDemand = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> target) {
            target.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long demand) {
                    requestCalls.incrementAndGet();
                    largestDemand.accumulateAndGet(Math.toIntExact(demand), Math::max);
                    if (!emitted) {
                        emitted = true;
                        target.onNext(ByteBuffer.wrap("one".getBytes(StandardCharsets.UTF_8)));
                    }
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }
    }
}
