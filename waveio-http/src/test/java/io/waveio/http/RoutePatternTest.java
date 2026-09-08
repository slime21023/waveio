package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class RoutePatternTest {
    @Test void matchesExactSegmentsAndDecodesParameterOnce() {
        assertEquals("alice smith", RoutePattern.of("/users/{name}").match("/users/alice%20smith").get("name"));
        assertTrue(RoutePattern.of("/users/{name}").match("/users/").isEmpty());
        assertTrue(RoutePattern.of("/users/{name}").match("/users/alice/").isEmpty());
    }
    @Test void rejectsEncodedSlashAndMalformedEncoding() {
        assertTrue(RoutePattern.of("/users/{name}").match("/users/a%2Fb").isEmpty());
        assertTrue(RoutePattern.of("/users/{name}").match("/users/%ZZ").isEmpty());
    }
}
