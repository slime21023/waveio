package io.wavejava.wave.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CookieCodecTest {
    @Test
    void decodesRequestCookiesAcrossHeadersWithoutLettingMalformedPairsPoisonOthers() {
        var headers = Headers.builder()
                .add("Cookie", "theme=dark; invalid; session=abc")
                .add("Cookie", "locale=zh-TW")
                .build();

        assertEquals(
                java.util.List.of(Cookie.of("theme", "dark"), Cookie.of("session", "abc"), Cookie.of("locale", "zh-TW")),
                CookieCodec.decodeRequestHeaders(headers));
    }

    @Test
    void roundTripsSupportedSetCookieAttributesAndRejectsMalformedSecurityAttributes() {
        var cookie = Cookie.builder("session", "abc")
                .path("/")
                .domain("example.test")
                .maxAge(Duration.ofMinutes(5))
                .expires(Instant.parse("2026-09-09T00:00:00Z"))
                .secure(true)
                .httpOnly(true)
                .sameSite(Cookie.SameSite.LAX)
                .build();

        assertEquals(cookie, CookieCodec.decodeSetCookie(CookieCodec.encodeSetCookie(cookie)).orElseThrow());
        assertFalse(CookieCodec.decodeSetCookie("session=abc; Secure=yes").isPresent());
        assertTrue(CookieCodec.decodeSetCookie("session=abc; Unknown=value; HttpOnly").isPresent());
    }
}
