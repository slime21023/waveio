package io.waveio.http.internal.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutePatternTest {

    @Test
    void parsesAndNormalizesPatterns() {
        var pattern = new RoutePattern("/users/:id/profile/");
        assertEquals("/users/:id/profile", pattern.value());
        assertEquals(1, pattern.specificityCost()); // 0 + 1 + 0 = 1

        var rootPattern = new RoutePattern("/");
        assertEquals("/", rootPattern.value());
        assertEquals(0, rootPattern.specificityCost());
    }

    @Test
    void rejectsInvalidPatterns() {
        assertThrows(IllegalArgumentException.class, () -> new RoutePattern(null));
        assertThrows(IllegalArgumentException.class, () -> new RoutePattern(""));
        assertThrows(IllegalArgumentException.class, () -> new RoutePattern("   "));
        assertThrows(IllegalArgumentException.class, () -> new RoutePattern("no-slash"));
        assertThrows(IllegalArgumentException.class, () -> new RoutePattern("/users/:"));
        assertThrows(IllegalArgumentException.class, () -> new RoutePattern("/users/:id/sub/:id"));
        assertThrows(IllegalArgumentException.class, () -> new RoutePattern("/files/*path/more"));
    }

    @Test
    void parsesUnnamedWildcard() {
        var pattern = new RoutePattern("/files/*");
        assertEquals("/files/*", pattern.value());
        assertEquals(100, pattern.specificityCost());

        var match = pattern.match("/files/a/b/c");
        assertNotNull(match);
        assertEquals("a/b/c", match.get("*"));
    }

    @Test
    void conflictsWith() {
        var p1 = new RoutePattern("/users/:id");
        var p2 = new RoutePattern("/users/:name");
        var p3 = new RoutePattern("/users/me");
        var p4 = new RoutePattern("/users/:id/settings");
        var p5 = new RoutePattern("/items/:id");

        assertTrue(p1.conflictsWith(p2));
        assertFalse(p1.conflictsWith(p3));
        assertFalse(p1.conflictsWith(p4));
        assertFalse(p1.conflictsWith(p5));
    }

    @Test
    void matchesPathsCorrectly() {
        var staticPattern = new RoutePattern("/api/v1/health");
        assertNotNull(staticPattern.match("/api/v1/health"));
        assertNull(staticPattern.match("/api/v1/other"));
        assertNull(staticPattern.match("/api/v1"));
        assertNull(staticPattern.match("/api/v1/health/extra"));

        var paramPattern = new RoutePattern("/orgs/:org/repos/:repo");
        var match = paramPattern.match("/orgs/wave/repos/core");
        assertNotNull(match);
        assertEquals(Map.of("org", "wave", "repo", "core"), match);

        var wildcardPattern = new RoutePattern("/static/*filepath");
        var wildMatch = wildcardPattern.match("/static/css/main.css");
        assertNotNull(wildMatch);
        assertEquals("css/main.css", wildMatch.get("filepath"));

        var emptyWildMatch = wildcardPattern.match("/static");
        assertNotNull(emptyWildMatch);
        assertEquals("", emptyWildMatch.get("filepath"));

        assertNull(wildcardPattern.match("/other"));
    }

    @Test
    void joinsPrefixAndPath() {
        assertEquals("/", RoutePattern.join("", ""));
        assertEquals("/", RoutePattern.join("/", "/"));
        assertEquals("/api", RoutePattern.join("/api", ""));
        assertEquals("/api", RoutePattern.join("", "/api"));
        assertEquals("/api/v1", RoutePattern.join("/api", "/v1"));
        assertEquals("/api/v1", RoutePattern.join("/api/", "/v1/"));
        assertEquals("/api", RoutePattern.join("/api", "/"));
        assertEquals("/users", RoutePattern.join("/", "/users"));
        assertEquals("/users", RoutePattern.join("/", "/users/"));

        assertThrows(NullPointerException.class, () -> RoutePattern.join("/api", null));
        assertThrows(IllegalArgumentException.class, () -> RoutePattern.join("bad-prefix", "/path"));
        assertThrows(IllegalArgumentException.class, () -> RoutePattern.join("/api", "bad-path"));
    }
}
