package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class HttpMethodTest {

    @Test
    void verifiesAllStandardHttpMethods() {
        assertEquals(9, HttpMethod.values().length);
        assertNotNull(HttpMethod.valueOf("GET"));
        assertNotNull(HttpMethod.valueOf("HEAD"));
        assertNotNull(HttpMethod.valueOf("POST"));
        assertNotNull(HttpMethod.valueOf("PUT"));
        assertNotNull(HttpMethod.valueOf("PATCH"));
        assertNotNull(HttpMethod.valueOf("DELETE"));
        assertNotNull(HttpMethod.valueOf("OPTIONS"));
        assertNotNull(HttpMethod.valueOf("TRACE"));
        assertNotNull(HttpMethod.valueOf("CONNECT"));
    }
}
