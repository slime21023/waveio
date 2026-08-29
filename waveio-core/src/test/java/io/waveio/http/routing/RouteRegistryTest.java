package io.waveio.http.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpHeaders;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.body.RequestBody;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class RouteRegistryTest {

    @Test
    void routerBuilderSupportsAllConvenienceMethods() {
        var router = Router.builder()
                .route(HttpMethod.GET, "/custom-route", req -> HttpResponse.noContent())
                .blockingRoute(HttpMethod.POST, "/custom-blocking", req -> HttpResponse.noContent())
                .get("/get", req -> HttpResponse.noContent())
                .head("/head", req -> HttpResponse.noContent())
                .post("/post", req -> HttpResponse.noContent())
                .put("/put", req -> HttpResponse.noContent())
                .patch("/patch", req -> HttpResponse.noContent())
                .delete("/delete", req -> HttpResponse.noContent())
                .options("/options", req -> HttpResponse.noContent())
                .blockingGet("/b-get", req -> HttpResponse.noContent())
                .blockingPost("/b-post", req -> HttpResponse.noContent())
                .blockingPut("/b-put", req -> HttpResponse.noContent())
                .blockingPatch("/b-patch", req -> HttpResponse.noContent())
                .blockingDelete("/b-delete", req -> HttpResponse.noContent())
                .asyncRoute(HttpMethod.GET, "/async-custom",
                        req -> CompletableFuture.completedFuture(HttpResponse.noContent()))
                .asyncGet("/async-get", req -> CompletableFuture.completedFuture(HttpResponse.noContent()))
                .asyncHead("/async-head", req -> CompletableFuture.completedFuture(HttpResponse.noContent()))
                .asyncPost("/async-post", req -> CompletableFuture.completedFuture(HttpResponse.noContent()))
                .asyncPut("/async-put", req -> CompletableFuture.completedFuture(HttpResponse.noContent()))
                .asyncPatch("/async-patch", req -> CompletableFuture.completedFuture(HttpResponse.noContent()))
                .asyncDelete("/async-delete", req -> CompletableFuture.completedFuture(HttpResponse.noContent()))
                .asyncOptions("/async-options", req -> CompletableFuture.completedFuture(HttpResponse.noContent()))
                .install(r -> r.get("/installed-route", req -> HttpResponse.noContent()))
                .build();

        assertTrue(router.match(req(HttpMethod.GET, "/custom-route")).isPresent());
        var customBlocking = router.match(req(HttpMethod.POST, "/custom-blocking")).orElseThrow();
        assertTrue(customBlocking.blocking());

        assertTrue(router.match(req(HttpMethod.GET, "/get")).isPresent());
        assertTrue(router.match(req(HttpMethod.HEAD, "/head")).isPresent());
        assertTrue(router.match(req(HttpMethod.POST, "/post")).isPresent());
        assertTrue(router.match(req(HttpMethod.PUT, "/put")).isPresent());
        assertTrue(router.match(req(HttpMethod.PATCH, "/patch")).isPresent());
        assertTrue(router.match(req(HttpMethod.DELETE, "/delete")).isPresent());
        assertTrue(router.match(req(HttpMethod.OPTIONS, "/options")).isPresent());

        var bGet = router.match(req(HttpMethod.GET, "/b-get")).orElseThrow();
        assertTrue(bGet.blocking());

        assertTrue(router.match(req(HttpMethod.GET, "/installed-route")).isPresent());
        assertTrue(router.match(req(HttpMethod.GET, "/async-custom")).orElseThrow().asynchronous());
        assertTrue(router.match(req(HttpMethod.GET, "/async-get")).orElseThrow().asynchronous());
        assertTrue(router.match(req(HttpMethod.HEAD, "/async-head")).orElseThrow().asynchronous());
        assertTrue(router.match(req(HttpMethod.POST, "/async-post")).orElseThrow().asynchronous());
        assertTrue(router.match(req(HttpMethod.PUT, "/async-put")).orElseThrow().asynchronous());
        assertTrue(router.match(req(HttpMethod.PATCH, "/async-patch")).orElseThrow().asynchronous());
        assertTrue(router.match(req(HttpMethod.DELETE, "/async-delete")).orElseThrow().asynchronous());
        assertTrue(router.match(req(HttpMethod.OPTIONS, "/async-options")).orElseThrow().asynchronous());

        var bPost = router.match(req(HttpMethod.POST, "/b-post")).orElseThrow();
        assertTrue(bPost.blocking());

        var bPut = router.match(req(HttpMethod.PUT, "/b-put")).orElseThrow();
        assertTrue(bPut.blocking());

        var bPatch = router.match(req(HttpMethod.PATCH, "/b-patch")).orElseThrow();
        assertTrue(bPatch.blocking());

        var bDelete = router.match(req(HttpMethod.DELETE, "/b-delete")).orElseThrow();
        assertTrue(bDelete.blocking());
    }

    @Test
    void routerAllowedMethodsQueriesTable() {
        var router = Router.builder()
                .get("/resource", req -> HttpResponse.noContent())
                .post("/resource", req -> HttpResponse.noContent())
                .build();

        assertEquals(Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST),
                router.allowedMethods("/resource"));
        assertTrue(router.allowedMethods("/missing").isEmpty());
    }

    @Test
    void routeRegistryDefaultMethodsWorkOnCustomRegistry() {
        class CustomRegistry implements RouteRegistry {
            HttpMethod method;
            String path;
            boolean blocking;

            @Override
            public RouteRegistry route(HttpMethod method, String path, io.waveio.http.handler.HttpHandler handler) {
                this.method = method;
                this.path = path;
                this.blocking = false;
                return this;
            }

            @Override
            public RouteRegistry blockingRoute(HttpMethod method, String path, io.waveio.http.handler.HttpHandler handler) {
                this.method = method;
                this.path = path;
                this.blocking = true;
                return this;
            }

            @Override
            public RouteRegistry asyncRoute(HttpMethod method, String path,
                    io.waveio.http.handler.AsyncHttpHandler handler) {
                this.method = method;
                this.path = path;
                this.blocking = false;
                return this;
            }

            @Override public RouteRegistry use(io.waveio.http.middleware.Middleware middleware) { return this; }
            @Override public RouteRegistry group(String prefix, java.util.function.Consumer<RouteRegistry> routes) { return this; }
        }

        var custom = new CustomRegistry();
        custom.get("/1", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.GET, custom.method);
        assertFalse(custom.blocking);

        custom.head("/2", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.HEAD, custom.method);

        custom.post("/3", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.POST, custom.method);

        custom.put("/4", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.PUT, custom.method);

        custom.patch("/5", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.PATCH, custom.method);

        custom.delete("/6", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.DELETE, custom.method);

        custom.options("/7", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.OPTIONS, custom.method);

        custom.blockingGet("/8", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.GET, custom.method);
        assertTrue(custom.blocking);

        custom.blockingPost("/9", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.POST, custom.method);
        assertTrue(custom.blocking);

        custom.blockingPut("/10", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.PUT, custom.method);
        assertTrue(custom.blocking);

        custom.blockingPatch("/11", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.PATCH, custom.method);
        assertTrue(custom.blocking);

        custom.blockingDelete("/12", req -> HttpResponse.noContent());
        assertEquals(HttpMethod.DELETE, custom.method);
        assertTrue(custom.blocking);

        custom.asyncGet("/13", req -> CompletableFuture.completedFuture(HttpResponse.noContent()));
        assertEquals(HttpMethod.GET, custom.method);
        assertEquals("/13", custom.path);
        assertFalse(custom.blocking);
        assertThrows(UnsupportedOperationException.class, () -> custom.get("/metadata",
                io.waveio.http.routing.RouteMetadata.builder().name("named").build(),
                req -> HttpResponse.noContent()));

        custom.install(r -> r.get("/installed", req -> HttpResponse.noContent()));
        assertEquals(HttpMethod.GET, custom.method);
        assertEquals("/installed", custom.path);
    }

    private static HttpRequest req(HttpMethod method, String path) {
        return new HttpRequest(method, path, HttpHeaders.empty(), RequestBody.empty(), null, Map.of(), Map.of());
    }
}
