package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.waveio.task.Task;
import org.junit.jupiter.api.Test;

class RouteTableTest {
    private static final Handler HANDLER = context -> Task.success(null);
    @Test void distinguishesNotFoundMethodMismatchAndGetHead() {
        RouteTable table = RouteTable.builder().route(HttpMethod.GET, "/items/{id}", HANDLER).route(HttpMethod.POST, "/items/{id}", HANDLER).build();
        assertEquals(HttpStatus.NOT_FOUND, table.resolve(HttpMethod.GET, "/missing").status());
        RouteTable.Resolution mismatch = table.resolve(HttpMethod.PUT, "/items/1"); assertEquals(HttpStatus.METHOD_NOT_ALLOWED, mismatch.status()); assertEquals("GET, HEAD, POST", mismatch.allowHeader());
        assertTrue(table.resolve(HttpMethod.HEAD, "/items/1").matched()); assertFalse(table.resolve(HttpMethod.GET, "/items/1/").matched());
    }
}
