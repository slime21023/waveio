package io.waveio.http.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpHeaders;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.body.RequestBody;
import io.waveio.http.middleware.Middleware;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class RouterTest {
    @Test
    void exposesExplicitStreamingRouteWithoutChangingBufferedHandlers() throws Exception {
        var router = Router.builder()
                .streamingPost("/upload", (request, body) ->
                        CompletableFuture.completedFuture(HttpResponse.text("accepted")))
                .build();

        var match = router.match(request(HttpMethod.POST, "/upload")).orElseThrow();

        assertTrue(match.streaming());
        assertTrue(match.asynchronous());
        Flow.Publisher<ByteBuffer> body = subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override public void request(long amount) { subscriber.onComplete(); }
                    @Override public void cancel() {}
                });
        var response = match.streamingHandler().handle(match.request(), body)
                .toCompletableFuture().join();
        assertEquals("accepted", new String(((io.waveio.http.body.ResponseBody.Bytes)
                response.body()).value(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void extractsPathParameters() {
        var router = Router.builder()
                .get("/teams/:team/users/:user", request -> HttpResponse.noContent())
                .build();

        var match = router.match(request(HttpMethod.GET, "/teams/core/users/7"));

        assertTrue(match.isPresent());
        assertEquals("core", match.orElseThrow().request().pathParam("team").orElseThrow());
        assertEquals("7", match.orElseThrow().request().pathParam("user").orElseThrow());
    }

    @Test
    void rejectsAmbiguousRoutes() {
        var builder = Router.builder()
                .get("/users/:id", request -> HttpResponse.noContent());

        assertThrows(IllegalArgumentException.class,
                () -> builder.get("/users/:name", request -> HttpResponse.noContent()));
    }

    @Test
    void favorsStaticRouteOverParameterRegardlessOfRegistrationOrder() throws Exception {
        var router = Router.builder()
                .get("/users/:id", request -> HttpResponse.text("parameter"))
                .get("/users/me", request -> HttpResponse.status(HttpStatus.CREATED).build())
                .build();

        var match = router.match(request(HttpMethod.GET, "/users/me")).orElseThrow();

        assertEquals(HttpStatus.CREATED, match.handler().handle(match.request()).status());
    }

    @Test
    void wildcardCapturesRemainingPathAndHasLowestPrecedence() throws Exception {
        var router = Router.builder()
                .get("/assets/*path", request -> HttpResponse.text("wildcard"))
                .get("/assets/:file", request -> HttpResponse.text("parameter"))
                .get("/assets/logo.svg", request -> HttpResponse.status(HttpStatus.CREATED).build())
                .build();

        var staticMatch = router.match(request(HttpMethod.GET, "/assets/logo.svg")).orElseThrow();
        var wildcardMatch = router.match(request(HttpMethod.GET, "/assets/icons/dark.svg")).orElseThrow();

        assertEquals(HttpStatus.CREATED, staticMatch.handler().handle(staticMatch.request()).status());
        assertEquals("icons/dark.svg", wildcardMatch.request().pathParam("path").orElseThrow());
    }

    @Test
    void rejectsNonTerminalWildcard() {
        assertThrows(IllegalArgumentException.class,
                () -> Router.builder().get("/assets/*path/edit",
                        request -> HttpResponse.noContent()));
    }

    @Test
    void buildsImmutableSnapshot() {
        var builder = Router.builder().get("/first", request -> HttpResponse.noContent());
        var firstSnapshot = builder.build();
        builder.get("/second", request -> HttpResponse.noContent());
        var secondSnapshot = builder.build();

        assertFalse(firstSnapshot.match(request(HttpMethod.GET, "/second")).isPresent());
        assertTrue(secondSnapshot.match(request(HttpMethod.GET, "/second")).isPresent());
    }

    @Test
    void installsModulesAndNormalizesNestedGroups() {
        RouteModule users = routes -> routes.group("/users", group -> group
                .group("/:id", user -> user.delete("", request -> HttpResponse.noContent())));

        var router = Router.builder().group("/api", api -> api.install(users)).build();

        assertTrue(router.match(request(HttpMethod.DELETE, "/api/users/42")).isPresent());
    }

    @Test
    void groupMiddlewareAppliesRegardlessOfDeclarationOrder() {
        Middleware middleware = (request, chain) -> chain.next(request);

        var router = Router.builder()
                .group("/admin", admin -> admin
                        .get("/users", request -> HttpResponse.noContent())
                        .use(middleware))
                .build();

        var match = router.match(request(HttpMethod.GET, "/admin/users")).orElseThrow();
        assertEquals(1, match.middleware().size());
        assertEquals(middleware, match.middleware().getFirst());
    }

    @Test
    void recordsBlockingExecutionWithoutExposingExecutionEnum() {
        var router = Router.builder()
                .blockingGet("/users/:id", request -> HttpResponse.noContent())
                .build();

        assertTrue(router.match(request(HttpMethod.GET, "/users/1")).orElseThrow().blocking());
    }

    @Test
    void headFallsBackToGetButExplicitHeadTakesPrecedence() throws Exception {
        var fallbackRouter = Router.builder()
                .get("/resource", request -> HttpResponse.text("get"))
                .build();

        var fallback = fallbackRouter.match(request(HttpMethod.HEAD, "/resource")).orElseThrow();
        assertEquals("get", new String(((io.waveio.http.body.ResponseBody.Bytes)
                fallback.handler().handle(fallback.request()).body()).value()));
        assertEquals(HttpMethod.HEAD, fallback.request().method());

        var explicitRouter = Router.builder()
                .get("/resource", request -> HttpResponse.text("get"))
                .head("/resource", request -> HttpResponse.text("head"))
                .build();

        var explicit = explicitRouter.match(request(HttpMethod.HEAD, "/resource")).orElseThrow();
        assertEquals("head", new String(((io.waveio.http.body.ResponseBody.Bytes)
                explicit.handler().handle(explicit.request()).body()).value()));
    }

    @Test
    void allowedMethodsIncludesImplicitHeadForGetRoute() {
        var router = Router.builder()
                .get("/resource", request -> HttpResponse.noContent())
                .build();

        assertEquals(java.util.Set.of(HttpMethod.GET, HttpMethod.HEAD),
                router.allowedMethods("/resource"));
    }

    @Test
    void matchesCanonicalUnicodePathAndKeepsDecodedValueInsideOneSegment() {
        var router = Router.builder()
                .get("/café/:item", request -> HttpResponse.noContent())
                .build();

        var match = router.match(request(HttpMethod.GET, "/café/a b")).orElseThrow();

        assertEquals("a b", match.request().requirePathParam("item"));
        assertFalse(router.match(request(HttpMethod.GET, "/café/a/b")).isPresent());
    }

    @Test
    void exposesImmutableMetadataAndMatchedPathTemplate() {
        var metadata = io.waveio.http.routing.RouteMetadata.builder()
                .name("users.show")
                .attribute("resource", "user")
                .build();
        var router = Router.builder()
                .get("/users/:id", metadata, request -> HttpResponse.noContent())
                .build();

        var match = router.match(request(HttpMethod.GET, "/users/42")).orElseThrow();

        assertEquals("/users/:id", match.pathPattern());
        assertEquals(metadata, match.metadata());
    }

    @Test
    void requirePathParamReturnsValueOrReportsProgrammerError() {
        var matched = Router.builder()
                .get("/users/:id", request -> HttpResponse.noContent())
                .build()
                .match(request(HttpMethod.GET, "/users/42"))
                .orElseThrow()
                .request();

        assertEquals("42", matched.requirePathParam("id"));
        assertThrows(IllegalArgumentException.class, () -> matched.requirePathParam("missing"));
    }

    private static HttpRequest request(HttpMethod method, String path) {
        return new HttpRequest(method, path, HttpHeaders.empty(), RequestBody.empty(),
                null, Map.of(), Map.of());
    }
}
