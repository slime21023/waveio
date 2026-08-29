package io.waveio.http.internal.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpMethod;
import io.waveio.http.HttpResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteTableTest {

    @Test
    void sortsRoutesBySpecificityAndResolvesCorrectly() {
        var rWildcard = new RouteDefinition(HttpMethod.GET, new RoutePattern("/users/*rest"),
                false, req -> HttpResponse.text("wildcard"), List.of());
        var rParam = new RouteDefinition(HttpMethod.GET, new RoutePattern("/users/:id"),
                false, req -> HttpResponse.text("param"), List.of());
        var rStatic = new RouteDefinition(HttpMethod.GET, new RoutePattern("/users/me"),
                false, req -> HttpResponse.text("static"), List.of());
        var rPost = new RouteDefinition(HttpMethod.POST, new RoutePattern("/users/:id"),
                true, req -> HttpResponse.text("post"), List.of());

        // Construct in reverse order to verify sorting by specificityCost
        var table = new RouteTable(List.of(rWildcard, rParam, rStatic, rPost));

        var matchStatic = table.resolve(HttpMethod.GET, "/users/me").orElseThrow();
        assertEquals(rStatic.handler(), matchStatic.handler());
        assertFalse(matchStatic.blocking());

        var matchParam = table.resolve(HttpMethod.GET, "/users/123").orElseThrow();
        assertEquals(rParam.handler(), matchParam.handler());
        assertEquals("123", matchParam.pathParameters().get("id"));

        var matchWild = table.resolve(HttpMethod.GET, "/users/a/b/c").orElseThrow();
        assertEquals(rWildcard.handler(), matchWild.handler());
        assertEquals("a/b/c", matchWild.pathParameters().get("rest"));

        var matchPost = table.resolve(HttpMethod.POST, "/users/123").orElseThrow();
        assertEquals(rPost.handler(), matchPost.handler());
        assertTrue(matchPost.blocking());

        assertFalse(table.resolve(HttpMethod.DELETE, "/users/123").isPresent());
        assertFalse(table.resolve(HttpMethod.GET, "/unknown").isPresent());
    }

    @Test
    void returnsAllowedMethodsForPath() {
        var rGet = new RouteDefinition(HttpMethod.GET, new RoutePattern("/items/:id"),
                false, req -> HttpResponse.noContent(), List.of());
        var rPost = new RouteDefinition(HttpMethod.POST, new RoutePattern("/items/:id"),
                false, req -> HttpResponse.noContent(), List.of());
        var rPut = new RouteDefinition(HttpMethod.PUT, new RoutePattern("/items/:id"),
                false, req -> HttpResponse.noContent(), List.of());

        var table = new RouteTable(List.of(rGet, rPost, rPut));

        assertEquals(Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT),
                table.allowedMethods("/items/123"));
        assertTrue(table.allowedMethods("/other").isEmpty());
    }
}
