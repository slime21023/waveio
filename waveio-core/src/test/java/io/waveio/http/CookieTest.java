package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CookieTest {

    @Test
    void buildsMinimalCookie() {
        Cookie cookie = Cookie.builder("session", "abc123").build();
        assertEquals("session", cookie.name());
        assertEquals("abc123", cookie.value());
        assertEquals("session=abc123", cookie.toSetCookieHeader());
    }

    @Test
    void buildsFullCookie() {
        Cookie cookie = Cookie.builder("auth_token", "xyz-789")
                .path("/api")
                .domain("example.com")
                .maxAge(Duration.ofHours(2))
                .secure(true)
                .httpOnly(true)
                .sameSite(Cookie.SameSite.STRICT)
                .build();

        assertEquals("auth_token", cookie.name());
        assertEquals("xyz-789", cookie.value());
        assertEquals("auth_token=xyz-789; Path=/api; Domain=example.com; Max-Age=7200; Secure; HttpOnly; SameSite=Strict",
                cookie.toSetCookieHeader());
    }

    @Test
    void formatsSameSiteLaxAndNone() {
        Cookie laxCookie = Cookie.builder("c1", "v1")
                .sameSite(Cookie.SameSite.LAX)
                .build();
        assertEquals("c1=v1; SameSite=Lax", laxCookie.toSetCookieHeader());

        Cookie noneCookie = Cookie.builder("c2", "v2")
                .secure(true)
                .sameSite(Cookie.SameSite.NONE)
                .build();
        assertEquals("c2=v2; Secure; SameSite=None", noneCookie.toSetCookieHeader());
    }

    @Test
    void rejectsSameSiteNoneWithoutSecure() {
        var builder = Cookie.builder("c", "v").sameSite(Cookie.SameSite.NONE);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void rejectsInvalidCookieName() {
        assertThrows(NullPointerException.class, () -> Cookie.builder(null, "v").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("", "v").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("bad name", "v").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("name=1", "v").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("name;1", "v").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("name@domain", "v").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("name/slash", "v").build());
    }

    @Test
    void acceptsValidCookieNameCharacters() {
        String validName = "!#$%&'*+.^_`|~0-9A-Za-z-";
        Cookie cookie = Cookie.builder(validName, "valid").build();
        assertEquals(validName, cookie.name());
    }

    @Test
    void rejectsInvalidCookieValue() {
        assertThrows(NullPointerException.class, () -> Cookie.builder("c", null).build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "has space").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "has;semicolon").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "has,comma").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "has\"quote").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "has\\slash").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "has\u0000null").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "has\u0080unicode").build());
    }

    @Test
    void rejectsInvalidPathAndDomain() {
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").path("").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").path("   ").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").path("/a;b").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").path("/a\rb").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").path("/a\nb").build());

        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").domain("").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").domain("   ").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").domain("a;b").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").domain("a\rb").build());
        assertThrows(IllegalArgumentException.class, () -> Cookie.builder("c", "v").domain("a\nb").build());
    }

    @Test
    void validatesMaxAge() {
        assertThrows(IllegalArgumentException.class,
                () -> Cookie.builder("c", "v").maxAge(Duration.ofSeconds(-1)));

        Cookie cookie = Cookie.builder("c", "v").maxAge(Duration.ZERO).build();
        assertEquals("c=v; Max-Age=0", cookie.toSetCookieHeader());

        Cookie cookieNull = Cookie.builder("c", "v").maxAge(null).build();
        assertEquals("c=v", cookieNull.toSetCookieHeader());
    }
}
