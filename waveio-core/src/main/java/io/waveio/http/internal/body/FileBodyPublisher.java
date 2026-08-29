package io.waveio.http.internal.body;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class FileBodyPublisher implements Flow.Publisher<ByteBuffer> {
    private final Path path;
    private final int chunkSize;

    public FileBodyPublisher(Path path, int chunkSize) {
        this.path = path;
        this.chunkSize = chunkSize;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscriber.onSubscribe(new FileSubscription(path, chunkSize, subscriber));
    }

    private static final class FileSubscription implements Flow.Subscription {
        private final Path path;
        private final int chunkSize;
        private final Flow.Subscriber<? super ByteBuffer> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Semaphore signal = new Semaphore(0);

        private FileSubscription(Path path, int chunkSize,
                Flow.Subscriber<? super ByteBuffer> subscriber) {
            this.path = path;
            this.chunkSize = chunkSize;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long amount) {
            if (amount <= 0) {
                if (cancelled.compareAndSet(false, true)) {
                    subscriber.onError(new IllegalArgumentException("Demand must be positive"));
                }
                signal.release();
                return;
            }
            addDemand(amount);
            signal.release();
            if (started.compareAndSet(false, true)) Thread.startVirtualThread(this::publish);
        }

        @Override public void cancel() { cancelled.set(true); signal.release(); }

        private void publish() {
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
                while (!cancelled.get()) {
                    waitForDemand();
                    if (cancelled.get()) return;
                    var buffer = ByteBuffer.allocate(chunkSize);
                    int count = channel.read(buffer);
                    if (count < 0) {
                        cancelled.set(true);
                        subscriber.onComplete();
                        return;
                    }
                    if (count == 0) continue;
                    demand.decrementAndGet();
                    buffer.flip();
                    subscriber.onNext(buffer.asReadOnlyBuffer());
                }
            } catch (Throwable failure) {
                if (cancelled.compareAndSet(false, true)) subscriber.onError(failure);
            }
        }

        private void waitForDemand() throws InterruptedException {
            while (demand.get() == 0 && !cancelled.get()) signal.acquire();
        }

        private void addDemand(long amount) {
            demand.getAndUpdate(current -> {
                long updated = current + amount;
                return updated < 0 ? Long.MAX_VALUE : updated;
            });
        }
    }
}
