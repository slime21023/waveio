package io.wavejava.wave.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeadlineTest {
    @Test
    void deadlineUsesCallerSuppliedTimeWithoutChangingItsExpiry() {
        var expiresAt = Instant.parse("2030-01-01T00:00:10Z");
        var deadline = Deadline.at(expiresAt);

        assertEquals(expiresAt, deadline.expiresAt());
        assertFalse(deadline.isExpired(Instant.parse("2030-01-01T00:00:05Z")));
        assertTrue(deadline.isExpired(expiresAt));
        assertEquals(Duration.ofSeconds(5), deadline.remaining(Instant.parse("2030-01-01T00:00:05Z")));
        assertEquals(Duration.ZERO, deadline.remaining(Instant.parse("2030-01-01T00:00:15Z")));
        assertEquals(Deadline.at(expiresAt), deadline);
    }
}
