package io.wavejava.wave.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.Request;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class ForwardedHeaderPolicyTest {
    @Test
    void appliesOneCompleteForwardedOriginOnlyForATrustedImmediatePeer() {
        var policy = ForwardedHeaderPolicy.builder().trustedProxy("127.0.0.1/32").build();
        var request = request("127.0.0.1", Headers.builder()
                .add("Host", "internal.example:8080")
                .add("Forwarded", "for=192.0.2.10;proto=https;host=public.example:8443")
                .build());

        var publicAddress = policy.apply(request).publicAddress().orElseThrow();
        assertEquals(PublicAddress.of("https", "public.example", 8443), publicAddress);

        var untrusted = request("10.0.0.9", request.headers());
        assertEquals("http://internal.example:8080", policy.apply(untrusted).publicAddress().orElseThrow().toString());
    }

    @Test
    void failsClosedForAmbiguousOrMalformedForwardingValues() {
        var policy = ForwardedHeaderPolicy.builder()
                .trustedProxy("127.0.0.1/32")
                .allowLegacyXForwarded(true)
                .build();
        var ambiguous = request("127.0.0.1", Headers.builder()
                .add("Host", "internal.example")
                .add("Forwarded", "proto=https;host=first.example,proto=http;host=second.example")
                .add("X-Forwarded-Proto", "https")
                .add("X-Forwarded-Host", "legacy.example")
                .build());
        assertEquals("http://internal.example", policy.apply(ambiguous).publicAddress().orElseThrow().toString());

        var incomplete = request("127.0.0.1", Headers.builder()
                .add("Host", "internal.example")
                .add("Forwarded", "proto=https")
                .build());
        assertEquals("http://internal.example", policy.apply(incomplete).publicAddress().orElseThrow().toString());
    }

    @Test
    void legacyHeadersRequireExplicitOptInAndOneValueEach() {
        var headers = Headers.builder()
                .add("Host", "internal.example")
                .add("X-Forwarded-Proto", "https")
                .add("X-Forwarded-Host", "legacy.example")
                .build();
        var request = request("127.0.0.1", headers);
        assertEquals("http://internal.example", ForwardedHeaderPolicy.builder()
                .trustedProxy("127.0.0.1/32").build().apply(request).publicAddress().orElseThrow().toString());
        assertEquals("https://legacy.example", ForwardedHeaderPolicy.builder()
                .trustedProxy("127.0.0.1/32")
                .allowLegacyXForwarded(true)
                .build()
                .apply(request)
                .publicAddress()
                .orElseThrow()
                .toString());

        assertThrows(IllegalArgumentException.class, () -> ForwardedHeaderPolicy.builder().trustedProxy("proxy.example/24"));
        assertFalse(ForwardedHeaderPolicy.disabled().isEnabled());
    }

    private static Request request(String peer, Headers headers) {
        return Request.builder()
                .headers(headers)
                .remoteAddress(new InetSocketAddress(peer, 12_345))
                .build();
    }
}
