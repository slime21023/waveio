package io.waveio.http;

/**
 * Standard and custom HTTP response status codes and reasons.
 *
 * <p>Validates that the integer status code is within RFC specifications (100–599) and that
 * the textual reason phrase is non-blank.
 *
 * @param code numeric HTTP status code (100–599)
 * @param reason standard or custom reason phrase
 */
public record HttpStatus(int code, String reason) {
    /** 200 OK */
    public static final HttpStatus OK = new HttpStatus(200, "OK");
    /** 201 Created */
    public static final HttpStatus CREATED = new HttpStatus(201, "Created");
    /** 202 Accepted */
    public static final HttpStatus ACCEPTED = new HttpStatus(202, "Accepted");
    /** 204 No Content */
    public static final HttpStatus NO_CONTENT = new HttpStatus(204, "No Content");

    /** 301 Moved Permanently */
    public static final HttpStatus MOVED_PERMANENTLY = new HttpStatus(301, "Moved Permanently");
    /** 302 Found */
    public static final HttpStatus FOUND = new HttpStatus(302, "Found");
    /** 304 Not Modified */
    public static final HttpStatus NOT_MODIFIED = new HttpStatus(304, "Not Modified");

    /** 400 Bad Request */
    public static final HttpStatus BAD_REQUEST = new HttpStatus(400, "Bad Request");
    /** 401 Unauthorized */
    public static final HttpStatus UNAUTHORIZED = new HttpStatus(401, "Unauthorized");
    /** 403 Forbidden */
    public static final HttpStatus FORBIDDEN = new HttpStatus(403, "Forbidden");
    /** 404 Not Found */
    public static final HttpStatus NOT_FOUND = new HttpStatus(404, "Not Found");
    /** 405 Method Not Allowed */
    public static final HttpStatus METHOD_NOT_ALLOWED = new HttpStatus(405, "Method Not Allowed");
    /** 409 Conflict */
    public static final HttpStatus CONFLICT = new HttpStatus(409, "Conflict");
    /** 413 Payload Too Large */
    public static final HttpStatus PAYLOAD_TOO_LARGE = new HttpStatus(413, "Payload Too Large");
    /** 415 Unsupported Media Type */
    public static final HttpStatus UNSUPPORTED_MEDIA_TYPE =
            new HttpStatus(415, "Unsupported Media Type");
    /** 422 Unprocessable Content */
    public static final HttpStatus UNPROCESSABLE_CONTENT =
            new HttpStatus(422, "Unprocessable Content");
    /** 429 Too Many Requests */
    public static final HttpStatus TOO_MANY_REQUESTS = new HttpStatus(429, "Too Many Requests");

    /** 500 Internal Server Error */
    public static final HttpStatus INTERNAL_SERVER_ERROR =
            new HttpStatus(500, "Internal Server Error");
    /** 501 Not Implemented */
    public static final HttpStatus NOT_IMPLEMENTED = new HttpStatus(501, "Not Implemented");
    /** 502 Bad Gateway */
    public static final HttpStatus BAD_GATEWAY = new HttpStatus(502, "Bad Gateway");
    /** 503 Service Unavailable */
    public static final HttpStatus SERVICE_UNAVAILABLE =
            new HttpStatus(503, "Service Unavailable");
    /** 504 Gateway Timeout */
    public static final HttpStatus GATEWAY_TIMEOUT = new HttpStatus(504, "Gateway Timeout");

    public HttpStatus {
        if (code < 100 || code > 599) {
            throw new IllegalArgumentException("HTTP status code must be between 100 and 599");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("HTTP reason must not be blank");
        }
    }

    /**
     * Creates a custom HTTP status code with the given numeric code and reason phrase.
     *
     * @param code status code between 100 and 599
     * @param reason non-blank reason phrase
     * @return a new {@code HttpStatus} instance
     * @throws IllegalArgumentException if {@code code} is out of bounds or {@code reason} is blank
     */
    public static HttpStatus of(int code, String reason) {
        return new HttpStatus(code, reason);
    }
}
