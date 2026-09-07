package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class HttpValueTest {
    @Test void headersAreImmutableCaseInsensitiveSnapshots() {
        Headers.Builder builder = Headers.builder().add("Content-Type", "text/plain").add("content-type", "utf-8"); Headers headers = builder.build();
        assertEquals("text/plain", headers.first("CONTENT-TYPE")); assertEquals(2, headers.all("content-type").size());
    }
    @Test void uriPreservesRawPathAndQuery() {
        RequestUri uri = RequestUri.parse("/a%20b?x=one"); assertEquals("/a%20b", uri.path()); assertEquals("x=one", uri.query());
        assertThrows(IllegalArgumentException.class, () -> RequestUri.parse("relative"));
    }
}
