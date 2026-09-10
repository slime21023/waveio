package io.wavejava.wave.api.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SseEventTest {
    @Test
    void encodesEventMetadataMultilineDataAndCommentsAsUtf8() {
        var event = SseEvent.builder()
                .comment("alive\r\nstill")
                .event("update")
                .id("42")
                .retry(Duration.ofMillis(2_500))
                .data(" leading\nnext")
                .build();

        assertEquals(": alive\n: still\nevent: update\nid: 42\nretry: 2500\ndata:  leading\ndata: next\n\n",
                new String(event.encode(), StandardCharsets.UTF_8));
        assertEquals("update", event.event().orElseThrow());
        assertEquals("42", event.id().orElseThrow());
        assertEquals(Duration.ofMillis(2_500), event.retry().orElseThrow());
    }

    @Test
    void permitsAnEmptyIdAndUsesCommentHeartbeatWireForm() {
        var reset = SseEvent.builder().id("").build();

        assertTrue(reset.id().isPresent());
        assertEquals("", reset.id().orElseThrow());
        assertEquals("id:\n\n", new String(reset.encode(), StandardCharsets.UTF_8));
        assertEquals(":\n\n", new String(SseEvent.heartbeat().encode(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnsafeOrSemanticallyEmptyProtocolFields() {
        assertThrows(IllegalArgumentException.class, () -> SseEvent.builder().build());
        assertThrows(IllegalArgumentException.class, () -> SseEvent.builder().event("").build());
        assertThrows(IllegalArgumentException.class, () -> SseEvent.builder().event("bad\nname").build());
        assertThrows(IllegalArgumentException.class, () -> SseEvent.builder().id("bad\rvalue").build());
        assertThrows(IllegalArgumentException.class, () -> SseEvent.builder().data("bad\0value").build());
        assertThrows(IllegalArgumentException.class, () -> SseEvent.builder().retry(Duration.ofMillis(-1)).build());
    }
}
