package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.registry.Registry;
import io.waveio.task.Task;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RoutesTest {
    @Test
    void endpointRoutesExposePathParametersAndWrapMiddlewareInOrder() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        Handler handler = Routes.builder()
                .use((context, next) -> {
                    entered.set(true);
                    return next.proceed();
                })
                .get("/items/{id}", context -> {
                    assertEquals("42", context.pathParameters().get("id"));
                    return Task.success(HttpResponse.of(HttpStatus.OK));
                })
                .build(ErrorHandler.fallback());

        HttpResponse response = InMemoryExchange.execute(new HttpRequest(HttpMethod.GET, RequestUri.parse("/items/42"),
                Headers.empty()), Registry.empty(), handler, InMemoryExchange.defaultConfig());
        assertTrue(entered.get());
        assertEquals(HttpStatus.OK, response.status());
    }

    @Test
    void endpointFailuresUseTheApplicationErrorPolicyBeforeCommit() throws Exception {
        Handler handler = Routes.builder()
                .get("/broken", context -> Task.failure(new IllegalStateException("broken")))
                .build((context, failure) -> {
                    assertEquals("broken", failure.getMessage());
                    return Task.success(HttpResponse.of(HttpStatus.NOT_FOUND));
                });

        HttpResponse response = InMemoryExchange.execute(new HttpRequest(HttpMethod.GET, RequestUri.parse("/broken"),
                Headers.empty()), Registry.empty(), handler, InMemoryExchange.defaultConfig());
        assertEquals(HttpStatus.NOT_FOUND, response.status());
    }
}
