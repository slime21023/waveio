package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HttpStatusTest {

    @Test
    void verifiesStandardConstants() {
        assertEquals(200, HttpStatus.OK.code());
        assertEquals("OK", HttpStatus.OK.reason());

        assertEquals(201, HttpStatus.CREATED.code());
        assertEquals("Created", HttpStatus.CREATED.reason());

        assertEquals(204, HttpStatus.NO_CONTENT.code());
        assertEquals("No Content", HttpStatus.NO_CONTENT.reason());
        assertEquals(504, HttpStatus.GATEWAY_TIMEOUT.code());

        assertEquals(400, HttpStatus.BAD_REQUEST.code());
        assertEquals("Bad Request", HttpStatus.BAD_REQUEST.reason());

        assertEquals(404, HttpStatus.NOT_FOUND.code());
        assertEquals("Not Found", HttpStatus.NOT_FOUND.reason());

        assertEquals(405, HttpStatus.METHOD_NOT_ALLOWED.code());
        assertEquals("Method Not Allowed", HttpStatus.METHOD_NOT_ALLOWED.reason());

        assertEquals(413, HttpStatus.PAYLOAD_TOO_LARGE.code());
        assertEquals("Payload Too Large", HttpStatus.PAYLOAD_TOO_LARGE.reason());

        assertEquals(500, HttpStatus.INTERNAL_SERVER_ERROR.code());
        assertEquals("Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR.reason());
    }

    @Test
    void verifiesEverydayApplicationConstants() {
        assertEquals(202, HttpStatus.ACCEPTED.code());
        assertEquals("Accepted", HttpStatus.ACCEPTED.reason());
        assertEquals(301, HttpStatus.MOVED_PERMANENTLY.code());
        assertEquals("Moved Permanently", HttpStatus.MOVED_PERMANENTLY.reason());
        assertEquals(302, HttpStatus.FOUND.code());
        assertEquals(304, HttpStatus.NOT_MODIFIED.code());
        assertEquals("Not Modified", HttpStatus.NOT_MODIFIED.reason());
        assertEquals(401, HttpStatus.UNAUTHORIZED.code());
        assertEquals("Unauthorized", HttpStatus.UNAUTHORIZED.reason());
        assertEquals(403, HttpStatus.FORBIDDEN.code());
        assertEquals(409, HttpStatus.CONFLICT.code());
        assertEquals(415, HttpStatus.UNSUPPORTED_MEDIA_TYPE.code());
        assertEquals("Unsupported Media Type", HttpStatus.UNSUPPORTED_MEDIA_TYPE.reason());
        assertEquals(422, HttpStatus.UNPROCESSABLE_CONTENT.code());
        assertEquals(429, HttpStatus.TOO_MANY_REQUESTS.code());
        assertEquals("Too Many Requests", HttpStatus.TOO_MANY_REQUESTS.reason());
        assertEquals(501, HttpStatus.NOT_IMPLEMENTED.code());
        assertEquals(502, HttpStatus.BAD_GATEWAY.code());
        assertEquals(503, HttpStatus.SERVICE_UNAVAILABLE.code());
        assertEquals("Service Unavailable", HttpStatus.SERVICE_UNAVAILABLE.reason());
    }

    @Test
    void createsCustomStatus() {
        HttpStatus status = HttpStatus.of(418, "I'm a teapot");
        assertEquals(418, status.code());
        assertEquals("I'm a teapot", status.reason());

        assertEquals(new HttpStatus(418, "I'm a teapot"), status);
        assertEquals(new HttpStatus(418, "I'm a teapot").hashCode(), status.hashCode());
        assertNotEquals(HttpStatus.OK, status);
    }

    @Test
    void rejectsInvalidStatusCode() {
        assertThrows(IllegalArgumentException.class, () -> new HttpStatus(99, "Low"));
        assertThrows(IllegalArgumentException.class, () -> new HttpStatus(600, "High"));
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.of(99, "Low"));
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.of(600, "High"));
    }

    @Test
    void rejectsNullOrBlankReason() {
        assertThrows(IllegalArgumentException.class, () -> new HttpStatus(200, null));
        assertThrows(IllegalArgumentException.class, () -> new HttpStatus(200, ""));
        assertThrows(IllegalArgumentException.class, () -> new HttpStatus(200, "   "));
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.of(200, null));
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.of(200, ""));
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.of(200, "   "));
    }
}
