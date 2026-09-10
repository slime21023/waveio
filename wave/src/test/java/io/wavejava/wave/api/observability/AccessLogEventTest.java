package io.wavejava.wave.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.wavejava.wave.api.middleware.Outcome;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccessLogEventTest {
    @Test
    void retainsOnlyValidatedBoundedCompletionMetadata() {
        var event = new AccessLogEvent(
                "request-1",
                "GET",
                "/widgets/{id}",
                200,
                Instant.parse("2026-09-09T00:00:00Z"),
                Duration.ofMillis(4),
                12,
                Outcome.Kind.SUCCESS,
                AccessLogEvent.TransportOutcome.WRITTEN);

        assertEquals("request-1", event.requestId());
        assertEquals("/widgets/{id}", event.route());
        assertEquals(12, event.responseBodyBytes());
        assertEquals(AccessLogEvent.TransportOutcome.WRITTEN, event.transportOutcome());
    }

    @Test
    void permitsOnlyExplicitUnknownStatusAndStreamingByteSentinels() {
        var event = new AccessLogEvent(
                "request-2",
                "GET",
                "<pending>",
                0,
                Instant.EPOCH,
                Duration.ZERO,
                -1,
                Outcome.Kind.CLIENT_CANCELLATION,
                AccessLogEvent.TransportOutcome.CANCELLED);

        assertEquals(0, event.status());
        assertEquals(-1, event.responseBodyBytes());
        assertThrows(IllegalArgumentException.class, () -> new AccessLogEvent(
                "request-3",
                "GET",
                "/ok",
                99,
                Instant.EPOCH,
                Duration.ZERO,
                0,
                Outcome.Kind.SUCCESS,
                AccessLogEvent.TransportOutcome.WRITTEN));
        assertThrows(IllegalArgumentException.class, () -> new AccessLogEvent(
                "request-4",
                "GET\r\nforged",
                "/ok",
                200,
                Instant.EPOCH,
                Duration.ZERO,
                0,
                Outcome.Kind.SUCCESS,
                AccessLogEvent.TransportOutcome.WRITTEN));
    }
}
