package io.waveio.http;

/** An immutable three-digit HTTP response status. */
public record HttpStatus(int code, String reason) {
    /** Validates a status code and reason. */
    public HttpStatus {
        if (code < 100 || code > 599) { throw new IllegalArgumentException("status code must be 100 through 599"); }
        if (reason == null || reason.isBlank()) { throw new IllegalArgumentException("reason must not be blank"); }
    }
    /** 200 OK. */ public static final HttpStatus OK = new HttpStatus(200, "OK");
    /** 404 Not Found. */ public static final HttpStatus NOT_FOUND = new HttpStatus(404, "Not Found");
    /** 405 Method Not Allowed. */ public static final HttpStatus METHOD_NOT_ALLOWED = new HttpStatus(405, "Method Not Allowed");
    /** 500 Internal Server Error. */ public static final HttpStatus INTERNAL_SERVER_ERROR = new HttpStatus(500, "Internal Server Error");
}
