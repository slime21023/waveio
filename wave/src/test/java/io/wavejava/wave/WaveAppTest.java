package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.HttpException;
import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.middleware.Middleware;
import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.internal.http.ResponseDataReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaveAppTest {
    @Test
    void dispatchesPathParametersAndUnwindsMiddlewareInReverseOrder() {
        var events = new ArrayList<String>();
        var app = Wave.app()
                .middleware(recording("first", events))
                .middleware(recording("second", events))
                .routes(routes -> routes.get("/users/{id}", (request, response) -> {
                    events.add("handler:" + request.pathParameter("id").orElseThrow());
                    response.text("ok");
                }))
                .build();

        var response = app.handle(Request.of(HttpMethod.GET, "/users/42"));

        assertEquals(200, response.status());
        assertEquals(List.of("first:request", "second:request", "first:route", "second:route", "handler:42",
                "second:response:SUCCESS", "first:response:SUCCESS"), events);
    }

    @Test
    void shortCircuitStillUnwindsOnlyEnteredMiddleware() {
        var events = new ArrayList<String>();
        var app = Wave.app()
                .middleware(new Middleware() {
                    @Override
                    public void onRequest(Request request, Response response) {
                        events.add("first:request");
                        response.status(401).text("denied");
                    }

                    @Override
                    public void onResponse(Request request, Response response, Outcome outcome) {
                        events.add("first:response:" + outcome.kind());
                    }
                })
                .middleware(recording("second", events))
                .routes(routes -> routes.get("/", (request, response) -> response.text("unreachable")))
                .build();

        var response = app.handle(Request.of(HttpMethod.GET, "/"));

        assertEquals(401, response.status());
        assertEquals(List.of("first:request", "first:response:SUCCESS"), events);
    }

    @Test
    void convertsRoutingOutcomesToResponses() {
        var app = Wave.app().routes(routes -> routes.get("/items", (request, response) -> response.text("ok"))).build();

        var notFound = app.handle(Request.of(HttpMethod.GET, "/missing"));
        var methodNotAllowed = app.handle(Request.of(HttpMethod.POST, "/items"));
        var options = app.handle(Request.of(HttpMethod.OPTIONS, "/items"));

        assertEquals(404, notFound.status());
        assertEquals(405, methodNotAllowed.status());
        assertEquals("GET, HEAD, OPTIONS", methodNotAllowed.headers().first("Allow").orElseThrow());
        assertEquals(204, options.status());
        assertEquals("GET, HEAD, OPTIONS", options.headers().first("Allow").orElseThrow());
    }

    @Test
    void mapsHttpExceptionAndOpenHandlerFailureToProblems() {
        var app = Wave.app().routes(routes -> {
            routes.get("/missing", (request, response) -> {
                throw HttpException.notFound("missing_user");
            });
            routes.get("/open", (request, response) -> response.status(201));
        }).build();

        var missing = app.handle(Request.of(HttpMethod.GET, "/missing"));
        var open = app.handle(Request.of(HttpMethod.GET, "/open"));

        assertEquals(404, missing.status());
        assertEquals(500, open.status());
        assertEquals(io.wavejava.wave.internal.http.ResponseData.Kind.PROBLEM, ResponseDataReader.read(missing).kind());
        assertEquals(io.wavejava.wave.internal.http.ResponseData.Kind.PROBLEM, ResponseDataReader.read(open).kind());
    }

    @Test
    void appliesTheFirstRegisteredMatchingExceptionMapper() {
        var app = Wave.app()
                .exceptionMapper(IllegalArgumentException.class,
                        (failure, request, response) -> response.problem(Problem.of(422, "Invalid input")))
                .routes(routes -> routes.get("/", (request, response) -> {
                    throw new IllegalArgumentException("invalid");
                }))
                .build();

        var response = app.handle(Request.of(HttpMethod.GET, "/"));

        assertEquals(422, response.status());
    }

    private static Middleware recording(String name, List<String> events) {
        return new Middleware() {
            @Override
            public void onRequest(Request request, Response response) {
                events.add(name + ":request");
            }

            @Override
            public void onRoute(Request request, io.wavejava.wave.api.routing.RouteMatch route, Response response) {
                events.add(name + ":route");
            }

            @Override
            public void onResponse(Request request, Response response, Outcome outcome) {
                events.add(name + ":response:" + outcome.kind());
            }
        };
    }
}
