package io.waveio.http.internal.dispatch;

import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.middleware.HandlerChain;
import io.waveio.http.middleware.Middleware;
import io.waveio.http.routing.Router;
import io.waveio.http.server.ExceptionHandler;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class RequestDispatcher {
    private static final System.Logger LOG =
            System.getLogger(RequestDispatcher.class.getName());

    private final Router router;
    private final List<Middleware> middleware;
    private final ExceptionHandler exceptionHandler;
    private final Executor blockingExecutor;

    public RequestDispatcher(Router router, List<Middleware> middleware,
            ExceptionHandler exceptionHandler, Executor blockingExecutor) {
        this.router = router;
        this.middleware = List.copyOf(middleware);
        this.exceptionHandler = exceptionHandler;
        this.blockingExecutor = blockingExecutor;
    }

    public DispatchHandle dispatch(HttpRequest request, Consumer<HttpResponse> completion) {
        return dispatch(request, completion, ignored -> {});
    }

    public DispatchHandle dispatch(HttpRequest request, Consumer<HttpResponse> completion,
            Consumer<Throwable> failureConsumer) {
        var handle = new DispatchHandle();
        var match = router.match(request);
        if (match.isEmpty()) {
            handle.complete(unmatchedResponse(request), completion);
            return handle;
        }
        var resolved = match.get();
        if (resolved.streaming()) {
            var failure = new IllegalStateException(
                    "Streaming route requires a request-body publisher");
            reportFailure(failure, failureConsumer);
            handle.complete(failureResponse(failure, resolved.request()), completion);
            return handle;
        }
        if (resolved.asynchronous() && middleware.isEmpty() && resolved.middleware().isEmpty()) {
            invokeAsync(resolved, handle, completion, failureConsumer);
            return handle;
        }
        Runnable invocation = () -> handle.complete(
                invokeSafely(resolved, failureConsumer), completion);
        boolean useExecutor = resolved.blocking() || resolved.asynchronous();
        if (useExecutor && blockingExecutor instanceof ExecutorService service) {
            handle.attach(service.submit(invocation));
        } else if (useExecutor) blockingExecutor.execute(invocation);
        else invocation.run();
        return handle;
    }

    public DispatchHandle dispatchStreaming(HttpRequest request,
            Flow.Publisher<ByteBuffer> body, Consumer<HttpResponse> completion,
            Consumer<Throwable> failureConsumer) {
        java.util.Objects.requireNonNull(body, "body");
        var handle = new DispatchHandle();
        var match = router.match(request);
        if (match.isEmpty() || !match.get().streaming()) {
            var failure = new IllegalStateException(
                    "Request does not match a streaming route");
            reportFailure(failure, failureConsumer);
            handle.complete(failureResponse(failure, request), completion);
            return handle;
        }
        var resolved = match.get();
        if (middleware.isEmpty() && resolved.middleware().isEmpty()) {
            invokeStreamingAsync(resolved, body, handle, completion, failureConsumer);
            return handle;
        }
        Runnable invocation = () -> handle.complete(
                invokeStreamingSafely(resolved, body, failureConsumer), completion);
        if (blockingExecutor instanceof ExecutorService service) {
            handle.attach(service.submit(invocation));
        } else blockingExecutor.execute(invocation);
        return handle;
    }

    private void invokeStreamingAsync(Router.Match match, Flow.Publisher<ByteBuffer> body,
            DispatchHandle handle, Consumer<HttpResponse> completion,
            Consumer<Throwable> failureConsumer) {
        CompletionStage<HttpResponse> stage;
        try {
            stage = match.streamingHandler().handle(match.request(), body);
            if (stage == null) {
                throw new IllegalStateException("Streaming HTTP handler returned null stage");
            }
        } catch (Throwable failure) {
            completeFailure(match, handle, completion, failureConsumer, failure);
            return;
        }
        var future = stage.toCompletableFuture();
        handle.attach(future);
        stage.whenComplete((response, failure) -> {
            if (failure != null) {
                completeFailure(match, handle, completion, failureConsumer, failure);
            } else if (response == null) {
                completeFailure(match, handle, completion, failureConsumer,
                        new IllegalStateException(
                                "Streaming HTTP handler completed with null response"));
            } else {
                handle.complete(response, completion);
            }
        });
    }

    private void completeFailure(Router.Match match, DispatchHandle handle,
            Consumer<HttpResponse> completion, Consumer<Throwable> failureConsumer,
            Throwable failure) {
        var unwrapped = unwrap(failure);
        reportFailure(unwrapped, failureConsumer);
        handle.complete(failureResponse(unwrapped, match.request()), completion);
    }

    private HttpResponse invokeStreamingSafely(Router.Match match,
            Flow.Publisher<ByteBuffer> body, Consumer<Throwable> failureConsumer) {
        try {
            HandlerChain chain = request -> await(
                    match.streamingHandler().handle(request, body));
            var combined = new ArrayList<>(middleware);
            combined.addAll(match.middleware());
            for (int index = combined.size() - 1; index >= 0; index--) {
                var current = combined.get(index);
                var next = chain;
                chain = request -> current.handle(request, next);
            }
            var response = chain.next(match.request());
            if (response == null) {
                throw new IllegalStateException("Streaming HTTP handler returned null response");
            }
            return response;
        } catch (Throwable failure) {
            var unwrapped = unwrap(failure);
            reportFailure(unwrapped, failureConsumer);
            return failureResponse(unwrapped, match.request());
        }
    }

    private void invokeAsync(Router.Match match, DispatchHandle handle,
            Consumer<HttpResponse> completion, Consumer<Throwable> failureConsumer) {
        CompletionStage<HttpResponse> stage;
        try {
            stage = match.asyncHandler().handle(match.request());
            if (stage == null) throw new IllegalStateException("Async HTTP handler returned null stage");
        } catch (Throwable failure) {
            var unwrapped = unwrap(failure);
            reportFailure(unwrapped, failureConsumer);
            handle.complete(failureResponse(unwrapped, match.request()), completion);
            return;
        }
        var future = stage.toCompletableFuture();
        handle.attach(future);
        stage.whenComplete((response, failure) -> {
            if (failure != null) {
                var unwrapped = unwrap(failure);
                reportFailure(unwrapped, failureConsumer);
                handle.complete(failureResponse(unwrapped, match.request()), completion);
            } else if (response == null) {
                var nullFailure = new IllegalStateException(
                        "Async HTTP handler completed with null response");
                reportFailure(nullFailure, failureConsumer);
                handle.complete(failureResponse(nullFailure, match.request()), completion);
            } else {
                handle.complete(response, completion);
            }
        });
    }

    private HttpResponse invokeSafely(Router.Match match, Consumer<Throwable> failureConsumer) {
        try {
            var response = invoke(match);
            if (response == null) throw new IllegalStateException("HTTP handler returned null");
            return response;
        } catch (Throwable failure) {
            reportFailure(failure, failureConsumer);
            return failureResponse(failure, match.request());
        }
    }

    private void reportFailure(Throwable failure, Consumer<Throwable> consumer) {
        try {
            consumer.accept(failure);
        } catch (Throwable reportingFailure) {
            LOG.log(System.Logger.Level.TRACE,
                    "Failure reporting callback threw", reportingFailure);
            // Observability must never affect dispatch.
        }
    }

    public HttpResponse failureResponse(Throwable failure, HttpRequest request) {
        try {
            var response = exceptionHandler.handle(failure, request);
            return response != null
                    ? response : HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (Throwable mappingFailure) {
            return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private HttpResponse invoke(Router.Match match) throws Exception {
        HandlerChain chain = match.asynchronous()
                ? request -> await(match.asyncHandler().handle(request))
                : match.handler()::handle;
        var combined = new ArrayList<>(middleware);
        combined.addAll(match.middleware());
        for (int index = combined.size() - 1; index >= 0; index--) {
            var current = combined.get(index);
            var next = chain;
            chain = request -> current.handle(request, next);
        }
        return chain.next(match.request());
    }

    private HttpResponse await(CompletionStage<HttpResponse> stage) throws Exception {
        if (stage == null) throw new IllegalStateException("Async HTTP handler returned null stage");
        var future = stage.toCompletableFuture();
        try {
            return future.get();
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private HttpResponse unmatchedResponse(HttpRequest request) {
        var allowedMethods = router.allowedMethods(request.path());
        if (allowedMethods.isEmpty()) {
            return HttpResponse.status(HttpStatus.NOT_FOUND)
                    .header("content-type", "text/plain; charset=utf-8")
                    .body("Not Found");
        }
        return HttpResponse.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("content-type", "text/plain; charset=utf-8")
                .header("allow", allowedMethods.stream().map(Enum::name).sorted()
                        .collect(Collectors.joining(", ")))
                .body("Method Not Allowed");
    }

    public static final class DispatchHandle {
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile Future<?> future;

        private void attach(Future<?> value) {
            future = value;
            if (finished.get()) value.cancel(true);
        }

        private void complete(HttpResponse response, Consumer<HttpResponse> completion) {
            if (finished.compareAndSet(false, true)) completion.accept(response);
        }

        public void cancel() {
            if (!finished.compareAndSet(false, true)) return;
            var current = future;
            if (current != null) current.cancel(true);
        }
    }
}
