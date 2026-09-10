package io.wavejava.wave.api.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.MediaType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentNegotiationResolverTest {
    private final ContentNegotiationResolver resolver = new ContentNegotiationResolver();

    @Test
    void absentOrBlankAcceptDefaultsToTheFirstOfferedType() {
        var offered = List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);

        assertEquals(MediaType.APPLICATION_JSON, resolver.resolve(Headers.empty(), offered).orElseThrow());
        assertEquals(MediaType.APPLICATION_JSON, resolver.resolve("   ", offered).orElseThrow());
        assertTrue(resolver.resolve(Headers.empty(), List.of()).isEmpty());
    }

    @Test
    void qualitySpecificityAndWireOrderSelectOneDeterministicOffer() {
        var jsonAndText = List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);

        assertEquals(MediaType.TEXT_PLAIN,
                resolver.resolve("application/json; q=0.6, text/plain; q=0.8", jsonAndText).orElseThrow());
        assertEquals(MediaType.APPLICATION_JSON,
                resolver.resolve("application/json, text/plain", List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON))
                        .orElseThrow());

        var html = MediaType.of("text", "html");
        assertEquals(html,
                resolver.resolve("text/*;q=1, text/plain;q=0", List.of(MediaType.TEXT_PLAIN, html)).orElseThrow());
        assertTrue(resolver.resolve("*/*;q=0", jsonAndText).isEmpty());
    }

    @Test
    void matchesStructuredSuffixAndMediaParametersBeforeQuality() {
        var problemJson = MediaType.APPLICATION_PROBLEM_JSON;
        assertEquals(problemJson,
                resolver.resolve("application/*+json", List.of(MediaType.APPLICATION_JSON, problemJson)).orElseThrow());

        var utf8Text = MediaType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8);
        assertEquals(utf8Text, resolver.resolve(
                "text/plain; charset=UTF-8; q=0.8, text/plain; q=0.8",
                List.of(MediaType.TEXT_PLAIN, utf8Text)
        ).orElseThrow());
    }

    @Test
    void handlesMultipleHeaderFieldsAndRejectsMalformedInput() {
        assertEquals(MediaType.TEXT_PLAIN, resolver.resolve(
                List.of("application/json;q=0.2", "text/plain;q=0.8"),
                List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)
        ).orElseThrow());

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("application/json;q=1.1", List.of(MediaType.APPLICATION_JSON)));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("application/json,,text/plain", List.of(MediaType.APPLICATION_JSON)));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("application/json", List.of(MediaType.parse("application/*"))));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("application/json;q=1.1", List.of()));
    }
}
