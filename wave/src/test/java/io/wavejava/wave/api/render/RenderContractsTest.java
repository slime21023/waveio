package io.wavejava.wave.api.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.http.Request;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RenderContractsTest {
    @Test
    void renderedOwnsBytesAndMakesBodyHeadersUnambiguous() {
        var source = new byte[] {1, 2, 3};
        var rendered = Rendered.of(source, MediaType.APPLICATION_OCTET_STREAM, Headers.of("Cache-Control", "no-store"));
        source[0] = 99;

        assertArrayEquals(new byte[] {1, 2, 3}, rendered.bytes());
        var copy = rendered.bytes();
        copy[1] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, rendered.bytes());
        assertEquals(3, rendered.contentLength());
        assertEquals("no-store", rendered.headers().first("cache-control").orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> Rendered.of(new byte[0], MediaType.TEXT_PLAIN, Headers.of("Content-Type", "text/plain")));
    }

    @Test
    void renderContextCarriesOnlyAnOptionalRequestAndSelectedType() {
        var detached = RenderContext.of(MediaType.APPLICATION_JSON);
        var requestBound = RenderContext.of(Request.of(io.wavejava.wave.api.http.HttpMethod.GET, "/"), MediaType.TEXT_PLAIN);

        assertFalse(detached.request().isPresent());
        assertEquals(MediaType.APPLICATION_JSON, detached.mediaType());
        assertTrue(requestBound.request().isPresent());
        assertEquals(MediaType.TEXT_PLAIN, requestBound.mediaType());
        assertArrayEquals("wave".getBytes(StandardCharsets.UTF_8), Rendered.text("wave").bytes());
    }
}
