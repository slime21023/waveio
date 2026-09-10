package io.wavejava.wave.api.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.HttpMethod;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutesTest {
    private static final Handler STATIC_HANDLER = (request, response) -> { };
    private static final Handler PARAMETER_HANDLER = (request, response) -> { };
    private static final Handler WILDCARD_HANDLER = (request, response) -> { };

    @Test
    void selectsStaticBeforeParameterBeforeTerminalWildcard() {
        Routes routes = Routes.builder()
                .get("/assets/logo", STATIC_HANDLER)
                .get("/assets/{file}", PARAMETER_HANDLER)
                .get("/assets/{*path}", WILDCARD_HANDLER)
                .build();

        RouteMatch staticMatch = routes.match("GET", "/assets/logo");
        assertTrue(staticMatch.isMatched());
        assertSame(STATIC_HANDLER, staticMatch.requireHandler());
        assertEquals(Map.of(), staticMatch.pathParameters());

        RouteMatch parameterMatch = routes.match("GET", "/assets/readme.txt");
        assertTrue(parameterMatch.isMatched());
        assertSame(PARAMETER_HANDLER, parameterMatch.requireHandler());
        assertEquals(Map.of("file", "readme.txt"), parameterMatch.pathParameters());

        RouteMatch wildcardMatch = routes.match("GET", "/assets/docs/getting-started");
        assertTrue(wildcardMatch.isMatched());
        assertSame(WILDCARD_HANDLER, wildcardMatch.requireHandler());
        assertEquals(Map.of("path", "docs/getting-started"), wildcardMatch.pathParameters());

        RouteMatch emptyWildcardMatch = routes.match("GET", "/assets");
        assertTrue(emptyWildcardMatch.isMatched());
        assertSame(WILDCARD_HANDLER, emptyWildcardMatch.requireHandler());
        assertEquals(Map.of("path", ""), emptyWildcardMatch.pathParameters());
    }

    @Test
    void matchesMethodsAndMakesHeadAndOptionsAvailableAutomatically() {
        Handler get = (request, response) -> { };
        Handler post = (request, response) -> { };
        Routes routes = Routes.builder()
                .get("/items/{id}", get)
                .post("/items/{id}", post)
                .build();

        RouteMatch head = routes.match(HttpMethod.HEAD, "/items/42");
        assertTrue(head.isMatched());
        assertSame(get, head.requireHandler());
        assertEquals(HttpMethod.GET, head.requireRoute().method());
        assertTrue(head.isHeadFallback());
        assertEquals(Map.of("id", "42"), head.pathParameters());

        RouteMatch mismatch = routes.match("PUT", "/items/42");
        assertEquals(RouteMatch.Kind.METHOD_NOT_ALLOWED, mismatch.kind());
        assertEquals(List.of("GET", "HEAD", "POST", "OPTIONS"),
                mismatch.allowedMethods().stream().map(HttpMethod::name).toList());
        assertEquals("GET, HEAD, POST, OPTIONS", mismatch.allowHeader());

        RouteMatch options = routes.match("OPTIONS", "/items/42");
        assertEquals(RouteMatch.Kind.AUTOMATIC_OPTIONS, options.kind());
        assertEquals("GET, HEAD, POST, OPTIONS", options.allowHeader());
    }

    @Test
    void explicitHeadAndOptionsRoutesOverrideTheAutomaticPolicies() {
        Handler get = (request, response) -> { };
        Handler head = (request, response) -> { };
        Handler options = (request, response) -> { };
        Routes routes = Routes.builder()
                .get("/reports", get)
                .head("/reports", head)
                .options("/reports", options)
                .build();

        RouteMatch headMatch = routes.match("HEAD", "/reports");
        assertTrue(headMatch.isMatched());
        assertSame(head, headMatch.requireHandler());
        assertFalse(headMatch.isHeadFallback());

        RouteMatch optionsMatch = routes.match("OPTIONS", "/reports");
        assertTrue(optionsMatch.isMatched());
        assertSame(options, optionsMatch.requireHandler());
        assertFalse(optionsMatch.isAutomaticOptions());
    }

    @Test
    void distinguishesMissingPathsFromMethodMismatch() {
        Routes routes = Routes.builder().get("/items/{id}", PARAMETER_HANDLER).build();

        RouteMatch missing = routes.match("GET", "/missing");
        assertEquals(RouteMatch.Kind.NOT_FOUND, missing.kind());
        assertEquals("", missing.allowHeader());
        assertTrue(missing.allowedMethods().isEmpty());
        assertThrows(IllegalStateException.class, missing::requireHandler);
    }

    @Test
    void matchesCustomMethodsWithoutChangingTheirCase() {
        Handler purge = (request, response) -> { };
        Routes routes = Routes.builder().route("PURGE", "/cache", purge).build();

        assertSame(purge, routes.match("PURGE", "/cache").requireHandler());
        assertEquals(RouteMatch.Kind.METHOD_NOT_ALLOWED, routes.match("purge", "/cache").kind());
    }

    @Test
    void validatesDuplicateAndAmbiguousRouteShapesAtBuildTime() {
        assertThrows(RouteConfigurationException.class, () -> Routes.builder()
                .get("/items/{id}", PARAMETER_HANDLER)
                .get("/items/{slug}", STATIC_HANDLER)
                .build());

        assertThrows(RouteConfigurationException.class, () -> Routes.builder()
                .get("/files/*", WILDCARD_HANDLER)
                .get("/files/{*path}", PARAMETER_HANDLER)
                .build());

        assertThrows(IllegalArgumentException.class, () -> Routes.builder()
                .get("/files/*/metadata", WILDCARD_HANDLER));
    }

    @Test
    void allowsDifferentMethodsForTheSameParameterizedShape() {
        Handler get = (request, response) -> { };
        Handler post = (request, response) -> { };
        Routes routes = Routes.builder()
                .get("/items/{id}", get)
                .post("/items/{slug}", post)
                .build();

        RouteMatch getMatch = routes.match("GET", "/items/42");
        assertSame(get, getMatch.requireHandler());
        assertEquals(Map.of("id", "42"), getMatch.pathParameters());

        RouteMatch postMatch = routes.match("POST", "/items/42");
        assertSame(post, postMatch.requireHandler());
        assertEquals(Map.of("slug", "42"), postMatch.pathParameters());
    }

    @Test
    void composesNestedScopesWithoutChangingChildRouteContracts() {
        Routes routes = Routes.builder()
                .prefix("/v1", nested -> nested.get("/users/{id}", PARAMETER_HANDLER))
                .build();

        RouteMatch match = routes.match("GET", "/v1/users/42");
        assertTrue(match.isMatched());
        assertEquals("/v1/users/{id}", match.requireRoute().pattern());
        assertEquals(Map.of("id", "42"), match.pathParameters());
    }
}
