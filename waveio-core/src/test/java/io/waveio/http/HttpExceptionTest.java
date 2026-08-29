package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class HttpExceptionTest {

    @Test
    void constructsCustomHttpException() {
        var ex = new HttpException(HttpStatus.PAYLOAD_TOO_LARGE, "Too big");
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.status());
        assertEquals("Too big", ex.getMessage());
    }

    @Test
    void createsBadRequestException() {
        var ex = HttpException.badRequest("Invalid input");
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("Invalid input", ex.getMessage());
    }

    @Test
    void createsNotFoundException() {
        var ex = HttpException.notFound("Item missing");
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("Item missing", ex.getMessage());
    }

    @Test
    void preservesTheWrappedCause() {
        var cause = new IllegalStateException("underlying");
        var ex = new HttpException(HttpStatus.BAD_GATEWAY, "upstream failed", cause);

        assertEquals(HttpStatus.BAD_GATEWAY, ex.status());
        assertEquals("upstream failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void createsEverydayStatusExceptions() {
        assertEquals(HttpStatus.UNAUTHORIZED, HttpException.unauthorized("no token").status());
        assertEquals(HttpStatus.FORBIDDEN, HttpException.forbidden("denied").status());
        assertEquals(HttpStatus.CONFLICT, HttpException.conflict("exists").status());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                HttpException.unsupportedMediaType("not json").status());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT,
                HttpException.unprocessable("invalid").status());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                HttpException.tooManyRequests("slow down").status());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                HttpException.serviceUnavailable("draining").status());

        var custom = HttpException.of(HttpStatus.of(418, "I'm a teapot"), "short and stout");
        assertEquals(418, custom.status().code());
        assertEquals("short and stout", custom.getMessage());
    }
}
