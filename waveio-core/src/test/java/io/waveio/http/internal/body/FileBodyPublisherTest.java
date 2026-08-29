package io.waveio.http.internal.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBodyPublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsNullSubscriber() {
        var publisher = new FileBodyPublisher(tempDir.resolve("any.txt"), 1024);
        assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
    }

    @Test
    void publishesEntireFileInChunks() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        String content = "Hello WaveIO! " + "A".repeat(500);
        Files.writeString(file, content);

        var publisher = new FileBodyPublisher(file, 64);
        var output = new ByteArrayOutputStream();
        var completed = new CompletableFuture<Void>();

        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
                subscription.request(1);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(null);
            }
        });

        completed.get(5, TimeUnit.SECONDS);
        assertEquals(content, output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsNonPositiveDemand() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "test");

        var publisher = new FileBodyPublisher(file, 64);
        var errorFuture = new CompletableFuture<Throwable>();

        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(0);
            }

            @Override public void onNext(ByteBuffer item) {}

            @Override
            public void onError(Throwable throwable) {
                errorFuture.complete(throwable);
            }

            @Override public void onComplete() {}
        });

        Throwable err = errorFuture.get(5, TimeUnit.SECONDS);
        assertTrue(err instanceof IllegalArgumentException);
        assertEquals("Demand must be positive", err.getMessage());
    }

    @Test
    void cancelsSubscriptionGracefully() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "x".repeat(10_000));

        var publisher = new FileBodyPublisher(file, 64);
        var latch = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
                subscription.cancel();
                latch.countDown();
            }

            @Override public void onNext(ByteBuffer item) {}
            @Override public void onError(Throwable throwable) {}
            @Override public void onComplete() {}
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void reportsErrorForNonExistentFile() throws Exception {
        Path file = tempDir.resolve("non-existent.txt");
        var publisher = new FileBodyPublisher(file, 64);
        var errorFuture = new CompletableFuture<Throwable>();

        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
            }

            @Override public void onNext(ByteBuffer item) {}

            @Override
            public void onError(Throwable throwable) {
                errorFuture.complete(throwable);
            }

            @Override public void onComplete() {}
        });

        Throwable err = errorFuture.get(5, TimeUnit.SECONDS);
        assertTrue(err instanceof java.nio.file.NoSuchFileException);
    }

    @Test
    void handlesDemandOverflowAndLargeDemand() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "content");

        var publisher = new FileBodyPublisher(file, 64);
        var completed = new CompletableFuture<Void>();

        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                // Requesting Long.MAX_VALUE twice to test demand.getAndUpdate overflow handling
                subscription.request(Long.MAX_VALUE);
                subscription.request(Long.MAX_VALUE);
            }

            @Override public void onNext(ByteBuffer item) {}
            @Override public void onError(Throwable throwable) { completed.completeExceptionally(throwable); }
            @Override public void onComplete() { completed.complete(null); }
        });

        completed.get(5, TimeUnit.SECONDS);
    }
}
