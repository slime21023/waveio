package io.wavejava.wave.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpValuesTest {
    @Test
    void headersAreCaseInsensitiveImmutableAndRejectResponseSplitting() {
        var headers = Headers.builder().add("X-Trace", "one").add("x-trace", "two").build();

        assertEquals("one", headers.first("X-TRACE").orElseThrow());
        assertEquals(2, headers.all("x-Trace").size());
        assertThrows(UnsupportedOperationException.class, () -> headers.all("X-Trace").add("three"));
        assertThrows(IllegalArgumentException.class, () -> Headers.of("X-Test", "ok\r\nInjected: value"));
        assertEquals(headers, Headers.builder().add("x-trace", "one").add("X-Trace", "two").build());
    }

    @Test
    void mediaTypeNormalizesNamesAndRetainsSafeParameters() {
        var mediaType = MediaType.parse("Application/JSON; Charset=\"UTF-8\"");

        assertEquals("application", mediaType.type());
        assertEquals("json", mediaType.subtype());
        assertEquals("UTF-8", mediaType.parameter("CHARSET").orElseThrow());
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.matches(MediaType.parse("application/*+json")));
        assertEquals("application/json; charset=UTF-8", mediaType.toString());
        assertThrows(IllegalArgumentException.class, () -> MediaType.parse("text/plain\r\nX-Evil: yes"));
    }

    @Test
    void cookieRendersAttributesAndRequestParsingKeepsValidPairs() {
        var cookie = Cookie.builder("session", "abc")
                .path("/")
                .maxAge(Duration.ofMinutes(5))
                .secure(true)
                .httpOnly(true)
                .sameSite(Cookie.SameSite.LAX)
                .build();

        assertTrue(cookie.toSetCookieHeader().contains("Max-Age=300"));
        assertTrue(cookie.toSetCookieHeader().contains("SameSite=Lax"));
        assertEquals(2, Cookie.parseRequestHeader("a=1; broken; b=2").size());
        assertFalse(Cookie.parseRequestHeader("broken").iterator().hasNext());
    }
}
