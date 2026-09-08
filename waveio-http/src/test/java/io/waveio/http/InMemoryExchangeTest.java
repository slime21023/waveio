package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.waveio.registry.Registry;
import io.waveio.task.Task;
import org.junit.jupiter.api.Test;

class InMemoryExchangeTest {
    @Test void invokesAHandlerThroughTheProductionTaskAndExecutionModel() throws Exception {
        HttpRequest request = new HttpRequest(HttpMethod.GET, RequestUri.parse("/hello"), Headers.empty());
        HttpResponse response = InMemoryExchange.execute(request, Registry.empty(), context -> { context.respond(HttpResponse.of(HttpStatus.OK)); return Task.success(null); }, InMemoryExchange.defaultConfig());
        assertEquals(HttpStatus.OK, response.status());
    }
}
