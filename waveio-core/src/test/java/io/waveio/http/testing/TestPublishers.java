package io.waveio.http.testing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;

public final class TestPublishers {
    private TestPublishers() {}

    public static Flow.Publisher<ByteBuffer> strings(String... chunks) {
        var values = List.of(chunks);
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int index;
            private boolean terminated;

            @Override
            public void request(long amount) {
                if (terminated) return;
                if (amount <= 0) {
                    terminated = true;
                    subscriber.onError(new IllegalArgumentException("amount"));
                    return;
                }
                while (amount-- > 0 && index < values.size()) {
                    subscriber.onNext(ByteBuffer.wrap(
                            values.get(index++).getBytes(StandardCharsets.UTF_8)));
                }
                if (index == values.size()) {
                    terminated = true;
                    subscriber.onComplete();
                }
            }

            @Override public void cancel() { terminated = true; }
        });
    }
}
