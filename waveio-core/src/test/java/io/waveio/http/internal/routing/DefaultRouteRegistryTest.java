package io.waveio.http.internal.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpMethod;
import io.waveio.http.HttpResponse;
import io.waveio.http.routing.RouteModule;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DefaultRouteRegistryTest {

    @Test
    void registersAllHttpMethodsAndBlockingVariants() {
        var registry = new DefaultRouteRegistry();

        registry.get("/get", req -> HttpResponse.noContent());
        registry.head("/head", req -> HttpResponse.noContent());
        registry.post("/post", req -> HttpResponse.noContent());
        registry.put("/put", req -> HttpResponse.noContent());
        registry.patch("/patch", req -> HttpResponse.noContent());
        registry.delete("/delete", req -> HttpResponse.noContent());
        registry.options("/options", req -> HttpResponse.noContent());

        registry.blockingGet("/b-get", req -> HttpResponse.noContent());
        registry.blockingPost("/b-post", req -> HttpResponse.noContent());
        registry.blockingPut("/b-put", req -> HttpResponse.noContent());
        registry.blockingPatch("/b-patch", req -> HttpResponse.noContent());
        registry.blockingDelete("/b-delete", req -> HttpResponse.noContent());
        registry.asyncGet("/async", req -> CompletableFuture.completedFuture(HttpResponse.noContent()));

        var table = registry.buildTable();
        assertTrue(table.resolve(HttpMethod.GET, "/get").isPresent());
        assertTrue(table.resolve(HttpMethod.HEAD, "/head").isPresent());
        assertTrue(table.resolve(HttpMethod.POST, "/post").isPresent());
        assertTrue(table.resolve(HttpMethod.PUT, "/put").isPresent());
        assertTrue(table.resolve(HttpMethod.PATCH, "/patch").isPresent());
        assertTrue(table.resolve(HttpMethod.DELETE, "/delete").isPresent());
        assertTrue(table.resolve(HttpMethod.OPTIONS, "/options").isPresent());

        assertTrue(table.resolve(HttpMethod.GET, "/b-get").orElseThrow().blocking());
        assertTrue(table.resolve(HttpMethod.POST, "/b-post").orElseThrow().blocking());
        assertTrue(table.resolve(HttpMethod.PUT, "/b-put").orElseThrow().blocking());
        assertTrue(table.resolve(HttpMethod.PATCH, "/b-patch").orElseThrow().blocking());
        assertTrue(table.resolve(HttpMethod.DELETE, "/b-delete").orElseThrow().blocking());
        assertTrue(table.resolve(HttpMethod.GET, "/async").orElseThrow().asynchronous());
    }

    @Test
    void installsModule() {
        var registry = new DefaultRouteRegistry();
        RouteModule module = r -> r.get("/module-route", req -> HttpResponse.noContent());
        registry.install(module);

        var table = registry.buildTable();
        assertTrue(table.resolve(HttpMethod.GET, "/module-route").isPresent());
    }

    @Test
    void rejectsDuplicateRoutes() {
        var registry = new DefaultRouteRegistry();
        registry.get("/users/:id", req -> HttpResponse.noContent());

        assertThrows(IllegalArgumentException.class,
                () -> registry.get("/users/:userId", req -> HttpResponse.noContent()));
    }

    @Test
    void validatesNullArguments() {
        var registry = new DefaultRouteRegistry();
        assertThrows(NullPointerException.class, () -> registry.route(null, "/", req -> HttpResponse.noContent()));
        assertThrows(NullPointerException.class, () -> registry.route(HttpMethod.GET, "/", null));
        assertThrows(NullPointerException.class, () -> registry.asyncRoute(HttpMethod.GET, "/", null));
        assertThrows(NullPointerException.class, () -> registry.asyncRoute(null, "/",
                req -> CompletableFuture.completedFuture(HttpResponse.noContent())));
        assertThrows(NullPointerException.class, () -> registry.use(null));
        assertThrows(NullPointerException.class, () -> registry.group("/group", null));
    }
}
