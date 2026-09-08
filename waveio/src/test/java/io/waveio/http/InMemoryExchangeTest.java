package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.waveio.registry.Registry;
import io.waveio.task.Task;
import org.junit.jupiter.api.Test;
import java.util.List;

class InMemoryExchangeTest {
    @Test void invokesAHandlerThroughTheProductionTaskAndExecutionModel() throws Exception {
        HttpRequest request = new HttpRequest(HttpMethod.GET, RequestUri.parse("/hello"), Headers.empty());
        HttpResponse response = InMemoryExchange.execute(request, Registry.empty(), context -> { context.respond(HttpResponse.of(HttpStatus.OK)); return Task.success(null); }, InMemoryExchange.defaultConfig());
        assertEquals(HttpStatus.OK, response.status());
    }
    @Test void nextAndInsertRunChildHandlersInTheSameFixture() throws Exception {
        HttpRequest request = new HttpRequest(HttpMethod.GET, RequestUri.parse("/chain"), Headers.empty());
        Handler delegated = context -> context.next();
        Handler response = context -> { context.respond(HttpResponse.of(HttpStatus.OK)); return Task.success(null); };
        assertEquals(HttpStatus.OK, InMemoryExchange.execute(request, Registry.empty(), List.of(delegated, response), InMemoryExchange.defaultConfig()).status());
    }
    @Test void routerInvokesHandlerWithPathParametersAndPolicyResponses() throws Exception {
        Handler item = context -> { assertEquals("42", context.pathParameters().get("id")); context.respond(HttpResponse.of(HttpStatus.OK)); return Task.success(null); };
        Router router = new Router(RouteTable.builder().route(HttpMethod.GET, "/items/{id}", item).build());
        assertEquals(HttpStatus.OK, InMemoryExchange.execute(new HttpRequest(HttpMethod.GET, RequestUri.parse("/items/42"), Headers.empty()), Registry.empty(), router, InMemoryExchange.defaultConfig()).status());
        assertEquals(HttpStatus.NOT_FOUND, InMemoryExchange.execute(new HttpRequest(HttpMethod.GET, RequestUri.parse("/missing"), Headers.empty()), Registry.empty(), router, InMemoryExchange.defaultConfig()).status());
    }
}
