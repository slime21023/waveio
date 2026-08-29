package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpHeadersTest {

    @Test
    void emptyHeadersIsEmpty() {
        HttpHeaders headers = HttpHeaders.empty();
        assertTrue(headers.asMap().isEmpty());
        assertFalse(headers.first("Accept").isPresent());
        assertTrue(headers.all("Accept").isEmpty());
    }

    @Test
    void buildsHeadersWithAddAndSet() {
        HttpHeaders headers = HttpHeaders.builder()
                .add("Accept", "text/html")
                .add("Accept", "application/json")
                .set("Content-Type", "application/json")
                .set("Authorization", "Bearer 123")
                .add("X-Custom", "val1")
                .set("X-Custom", "val2")
                .build();

        assertEquals(List.of("text/html", "application/json"), headers.all("accept"));
        assertEquals("text/html", headers.first("ACCEPT").orElseThrow());
        assertEquals("application/json", headers.first("content-type").orElseThrow());
        assertEquals(List.of("val2"), headers.all("x-custom"));
    }

    @Test
    void asMapReturnsUnmodifiableNormalizedMap() {
        HttpHeaders headers = HttpHeaders.builder()
                .add("X-Header", "value")
                .build();

        var map = headers.asMap();
        assertEquals(List.of("value"), map.get("x-header"));
        assertThrows(UnsupportedOperationException.class, () -> map.put("other", List.of("v")));
    }

    @Test
    void rejectsNullOrBlankHeaderName() {
        var builder = HttpHeaders.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.add(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> builder.add("", "v"));
        assertThrows(IllegalArgumentException.class, () -> builder.add("   ", "v"));
        assertThrows(IllegalArgumentException.class, () -> builder.set(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> builder.set("", "v"));
        assertThrows(IllegalArgumentException.class, () -> builder.set("   ", "v"));

        HttpHeaders headers = HttpHeaders.empty();
        assertThrows(IllegalArgumentException.class, () -> headers.first(null));
        assertThrows(IllegalArgumentException.class, () -> headers.first(""));
        assertThrows(IllegalArgumentException.class, () -> headers.all(null));
        assertThrows(IllegalArgumentException.class, () -> headers.all(""));
    }

    @Test
    void rejectsNullHeaderValue() {
        var builder = HttpHeaders.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.add("Accept", null));
        assertThrows(NullPointerException.class, () -> builder.set("Accept", null));
    }
}
