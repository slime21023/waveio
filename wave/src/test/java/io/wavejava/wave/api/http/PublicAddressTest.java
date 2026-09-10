package io.wavejava.wave.api.http;

import io.wavejava.wave.api.http.PublicAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PublicAddressTest {
    @Test
    void parsesOnlySafeHttpOriginsAndNormalizesDefaultPorts() {
        var https = PublicAddress.parse("HTTPS", "Example.COM").orElseThrow();
        assertEquals("https", https.scheme());
        assertEquals("example.com", https.host());
        assertEquals(443, https.port());
        assertEquals("example.com", https.authority());
        assertEquals("https://example.com", https.origin().toString());

        var ipv6 = PublicAddress.parse("http", "[2001:db8::1]:8080").orElseThrow();
        assertEquals("2001:db8::1", ipv6.host());
        assertEquals("[2001:db8::1]:8080", ipv6.authority());
        assertEquals("http://[2001:db8::1]:8080/path?x=1", ipv6.resolve("/path?x=1").toString());
    }

    @Test
    void rejectsAuthorityInjectionAndUnsafeTargets() {
        assertFalse(PublicAddress.parse("http", "user@example.test").isPresent());
        assertFalse(PublicAddress.parse("http", "example.test/path").isPresent());
        assertFalse(PublicAddress.parse("ftp", "example.test").isPresent());
        assertFalse(PublicAddress.parse("https", "example.test\r\nInjected: yes").isPresent());

        var address = PublicAddress.of("https", "public.example", 443);
        assertThrows(IllegalArgumentException.class, () -> address.resolve("//attacker.example/"));
        assertThrows(IllegalArgumentException.class, () -> address.resolve("relative"));
    }
}
