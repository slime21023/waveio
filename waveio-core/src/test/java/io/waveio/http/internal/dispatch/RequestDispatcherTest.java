package io.waveio.http.internal.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpHeaders;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.body.RequestBody;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.Router;
import io.waveio.http.server.ExceptionHandler;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestDispatcherTest {

    @Test
    void dispatchesStreamingHandlerAndPreservesSynchronousMiddlewareSemantics() {
        var order = new ArrayList<String>();
        Middleware middleware = (request, chain) -> {
            order.add("before");
            var response = chain.next(request);
            order.add("after");
            return response;
        };
        var router = Router.builder()
                .use(middleware)
                .streamingPost("/upload", (request, body) -> {
                    order.add("handler");
                    return CompletableFuture.completedFuture(HttpResponse.text("ok"));
                })
                .build();
        var dispatcher = new RequestDispatcher(router, List.of(),
                (failure, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Runnable::run);
        var result = new CompletableFuture<HttpResponse>();
        Flow.Publisher<ByteBuffer> body = subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override public void request(long amount) { subscriber.onComplete(); }
                    @Override public void cancel() {}
                });

        dispatcher.dispatchStreaming(request(HttpMethod.POST, "/upload"), body,
                result::complete, failure -> {});

        assertEquals(HttpStatus.OK, result.join().status());
        assertEquals(List.of("before", "handler", "after"), order);
    }

    @Test
    void dispatchesUnmatchedPathTo404() {
        var router = Router.builder().get("/hello", req -> HttpResponse.text("ok")).build();
        var dispatcher = new RequestDispatcher(router, List.of(), (err, req) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(), Runnable::run);

        var future = new CompletableFuture<HttpResponse>();
        dispatcher.dispatch(request(HttpMethod.GET, "/unknown"), future::complete);

        var response = future.join();
        assertEquals(HttpStatus.NOT_FOUND, response.status());
    }

    @Test
    void dispatchesUnmatchedMethodTo405WithSortedAllowHeader() {
        var router = Router.builder()
                .post("/item", req -> HttpResponse.noContent())
                .get("/item", req -> HttpResponse.noContent())
                .delete("/item", req -> HttpResponse.noContent())
                .build();
        var dispatcher = new RequestDispatcher(router, List.of(), (err, req) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(), Runnable::run);

        var future = new CompletableFuture<HttpResponse>();
        dispatcher.dispatch(request(HttpMethod.PUT, "/item"), future::complete);

        var response = future.join();
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.status());
        assertEquals("DELETE, GET, HEAD, POST", response.headers().first("allow").orElseThrow());
    }

    @Test
    void dispatchesBlockingHandlerToBlockingExecutor() {
        var executedOnCustomExecutor = new AtomicBoolean();
        Executor customExecutor = task -> {
            executedOnCustomExecutor.set(true);
            task.run();
        };

        var router = Router.builder()
                .blockingGet("/blocking", req -> HttpResponse.noContent())
                .build();
        var dispatcher = new RequestDispatcher(router, List.of(), (err, req) -> null, customExecutor);

        var future = new CompletableFuture<HttpResponse>();
        dispatcher.dispatch(request(HttpMethod.GET, "/blocking"), future::complete);

        assertEquals(HttpStatus.NO_CONTENT, future.join().status());
        assertTrue(executedOnCustomExecutor.get());
    }

    @Test
    void handlesNullHandlerResponseViaExceptionHandler() {
        var router = Router.builder()
                .get("/null-resp", req -> null)
                .build();
        ExceptionHandler handler = (failure, req) -> HttpResponse.status(HttpStatus.of(502, "Bad Gateway")).body(failure.getMessage());
        var dispatcher = new RequestDispatcher(router, List.of(), handler, Runnable::run);

        var future = new CompletableFuture<HttpResponse>();
        dispatcher.dispatch(request(HttpMethod.GET, "/null-resp"), future::complete);

        var response = future.join();
        assertEquals(502, response.status().code());
    }

    @Test
    void fallsBackTo500WhenExceptionHandlerReturnsNullOrThrows() {
        var router = Router.builder()
                .get("/crash1", req -> { throw new RuntimeException("error 1"); })
                .get("/crash2", req -> { throw new RuntimeException("error 2"); })
                .build();

        var dispatcher1 = new RequestDispatcher(router, List.of(), (err, req) -> null, Runnable::run);
        var future1 = new CompletableFuture<HttpResponse>();
        dispatcher1.dispatch(request(HttpMethod.GET, "/crash1"), future1::complete);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, future1.join().status());

        var dispatcher2 = new RequestDispatcher(router, List.of(), (err, req) -> { throw new RuntimeException("handler broke"); }, Runnable::run);
        var future2 = new CompletableFuture<HttpResponse>();
        dispatcher2.dispatch(request(HttpMethod.GET, "/crash2"), future2::complete);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, future2.join().status());
    }

    @Test
    void combinesGlobalAndRouteMiddleware() {
        var execution = new ArrayList<String>();
        Middleware globalMiddleware = (req, chain) -> {
            execution.add("global-before");
            var res = chain.next(req);
            execution.add("global-after");
            return res;
        };
        Middleware routeMiddleware = (req, chain) -> {
            execution.add("route-before");
            var res = chain.next(req);
            execution.add("route-after");
            return res;
        };

        var router = Router.builder()
                .use(routeMiddleware)
                .get("/test", req -> {
                    execution.add("handler");
                    return HttpResponse.noContent();
                })
                .build();

        var dispatcher = new RequestDispatcher(router, List.of(globalMiddleware), (err, req) -> null, Runnable::run);
        var future = new CompletableFuture<HttpResponse>();
        dispatcher.dispatch(request(HttpMethod.GET, "/test"), future::complete);

        assertEquals(HttpStatus.NO_CONTENT, future.join().status());
        assertEquals(List.of("global-before", "route-before", "handler", "route-after", "global-after"), execution);
    }

    @Test
    void cancellingDispatchInterruptsBlockingHandlerAndSuppressesCompletion() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var router = Router.builder()
                    .blockingGet("/wait", request -> {
                        started.countDown();
                        try {
                            Thread.sleep(Duration.ofSeconds(30));
                        } catch (InterruptedException failure) {
                            interrupted.countDown();
                            throw failure;
                        }
                        return HttpResponse.noContent();
                    })
                    .build();
            var dispatcher = new RequestDispatcher(router, List.of(),
                    (failure, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                    executor);
            var completed = new AtomicBoolean();

            var dispatch = dispatcher.dispatch(request(HttpMethod.GET, "/wait"),
                    response -> completed.set(true));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            dispatch.cancel();

            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertFalse(completed.get());
        }
    }

    @Test
    void dispatchesAlreadyCompletedAsyncHandler() {
        var router = Router.builder()
                .asyncGet("/async", request -> CompletableFuture.completedFuture(
                        HttpResponse.text("async")))
                .build();
        var dispatcher = new RequestDispatcher(router, List.of(),
                (failure, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Runnable::run);
        var result = new CompletableFuture<HttpResponse>();

        dispatcher.dispatch(request(HttpMethod.GET, "/async"), result::complete);

        assertEquals("async", new String(((io.waveio.http.body.ResponseBody.Bytes)
                result.join().body()).value(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void mapsAsyncFailureNullStageAndNullResponse() {
        var expected = new IllegalStateException("async failure");
        var seen = new ArrayList<Throwable>();
        var router = Router.builder()
                .asyncGet("/failure", request -> CompletableFuture.failedFuture(
                        new CompletionException(expected)))
                .asyncGet("/null-stage", request -> null)
                .asyncGet("/null-response", request -> CompletableFuture.completedFuture(null))
                .build();
        var dispatcher = new RequestDispatcher(router, List.of(), (failure, request) -> {
            seen.add(failure);
            return HttpResponse.status(HttpStatus.of(502, "Mapped")).build();
        }, Runnable::run);

        for (String path : List.of("/failure", "/null-stage", "/null-response")) {
            var result = new CompletableFuture<HttpResponse>();
            dispatcher.dispatch(request(HttpMethod.GET, path), result::complete);
            assertEquals(502, result.join().status().code());
        }

        assertEquals(expected, seen.getFirst());
        assertTrue(seen.get(1) instanceof IllegalStateException);
        assertTrue(seen.get(2) instanceof IllegalStateException);
    }

    @Test
    void asyncCompletionMayArriveFromForeignThreadAndCanBeCancelled() throws Exception {
        var stage = new CompletableFuture<HttpResponse>();
        var router = Router.builder().asyncGet("/foreign", request -> stage).build();
        var dispatcher = new RequestDispatcher(router, List.of(),
                (failure, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                Runnable::run);
        var completionThread = new AtomicReference<Thread>();

        var dispatch = dispatcher.dispatch(request(HttpMethod.GET, "/foreign"),
                response -> completionThread.set(Thread.currentThread()));
        var foreign = Thread.ofPlatform().start(() -> stage.complete(HttpResponse.noContent()));
        foreign.join();

        assertEquals(foreign, completionThread.get());

        var cancelledStage = new CompletableFuture<HttpResponse>();
        var cancelRouter = Router.builder().asyncGet("/cancel", request -> cancelledStage).build();
        var cancelDispatcher = new RequestDispatcher(cancelRouter, List.of(),
                (failure, request) -> HttpResponse.noContent(), Runnable::run);
        var cancelled = cancelDispatcher.dispatch(request(HttpMethod.GET, "/cancel"), response -> {});
        cancelled.cancel();
        assertTrue(cancelledStage.isCancelled());
    }

    @Test
    void asyncRouteWithSynchronousMiddlewareUsesExecutorBridgeAndPreservesSemantics() {
        var order = new ArrayList<String>();
        var usedExecutor = new AtomicBoolean();
        Middleware middleware = (request, chain) -> {
            order.add("before");
            var response = chain.next(request);
            order.add("after");
            return response;
        };
        Executor executor = task -> {
            usedExecutor.set(true);
            task.run();
        };
        var router = Router.builder()
                .asyncGet("/with-middleware", request -> {
                    order.add("handler");
                    return CompletableFuture.completedFuture(HttpResponse.noContent());
                })
                .build();
        var dispatcher = new RequestDispatcher(router, List.of(middleware),
                (failure, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build(),
                executor);
        var result = new CompletableFuture<HttpResponse>();

        dispatcher.dispatch(request(HttpMethod.GET, "/with-middleware"), result::complete);

        assertEquals(HttpStatus.NO_CONTENT, result.join().status());
        assertTrue(usedExecutor.get());
        assertEquals(List.of("before", "handler", "after"), order);
    }

    private static HttpRequest request(HttpMethod method, String path) {
        return new HttpRequest(method, path, HttpHeaders.empty(), RequestBody.empty(), null, Map.of(), Map.of());
    }
}
