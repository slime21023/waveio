package io.wavejava.wave.api.sse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SseClientOptionsTest {
    @Test
    void rejectsHeaderInjectionAndControlCharactersInLastEventId() {
        for (var unsafe : java.util.List.of("one\rtwo", "one\ntwo", "one\u0000two", "one\u0001two", "one\u007ftwo")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SseClientOptions.builder().initialLastEventId(unsafe), unsafe);
        }
        assertDoesNotThrow(() -> SseClientOptions.builder().initialLastEventId("event\t42").build());
    }

    @Test
    void rejectsUnboundedOrNegativeConnectionAndReconnectLimits() {
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().connectTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().responseOpenTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().idleTimeout(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().initialReconnectDelay(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().maximumReconnectAttempts(-1));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().maximumHeaderBytes(0));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().maximumHeaderCount(0));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().maximumLineBytes(0));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().maximumEventBytes(0));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().maximumConnections(0));
        assertThrows(IllegalArgumentException.class, () -> SseClientOptions.builder().maximumReconnectDelay(Duration.ofNanos(-1)));
    }
}
