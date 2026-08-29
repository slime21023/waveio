package io.waveio.http;

/**
 * An application failure that already knows its HTTP status.
 *
 * <p>The default exception mapper turns this into a response with the given status and message,
 * so throwing one of these from a handler is the direct way to answer with a specific status.
 * Any other unhandled exception maps to {@code 500 Internal Server Error}.
 */
public class HttpException extends RuntimeException {
    private final HttpStatus status;

    /**
     * Constructs a new HTTP exception with the specified status and detail message.
     *
     * @param status the HTTP status to respond with
     * @param message descriptive error message
     */
    public HttpException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * Constructs a new HTTP exception with status, detail message, and underlying cause.
     *
     * @param status the HTTP status to respond with
     * @param message descriptive error message
     * @param cause underlying cause of the failure
     */
    public HttpException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * Returns the HTTP status associated with this exception.
     *
     * @return HTTP status
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Creates an exception for the given status and message.
     *
     * @param status target HTTP status
     * @param message error message
     * @return a new {@code HttpException}
     */
    public static HttpException of(HttpStatus status, String message) {
        return new HttpException(status, message);
    }

    /**
     * Creates an exception for the given status, message, and cause.
     *
     * @param status target HTTP status
     * @param message error message
     * @param cause root cause
     * @return a new {@code HttpException}
     */
    public static HttpException of(HttpStatus status, String message, Throwable cause) {
        return new HttpException(status, message, cause);
    }

    /**
     * Creates a {@code 400 Bad Request} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 400 status
     */
    public static HttpException badRequest(String message) {
        return new HttpException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Creates a {@code 401 Unauthorized} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 401 status
     */
    public static HttpException unauthorized(String message) {
        return new HttpException(HttpStatus.UNAUTHORIZED, message);
    }

    /**
     * Creates a {@code 403 Forbidden} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 403 status
     */
    public static HttpException forbidden(String message) {
        return new HttpException(HttpStatus.FORBIDDEN, message);
    }

    /**
     * Creates a {@code 404 Not Found} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 404 status
     */
    public static HttpException notFound(String message) {
        return new HttpException(HttpStatus.NOT_FOUND, message);
    }

    /**
     * Creates a {@code 409 Conflict} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 409 status
     */
    public static HttpException conflict(String message) {
        return new HttpException(HttpStatus.CONFLICT, message);
    }

    /**
     * Creates a {@code 415 Unsupported Media Type} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 415 status
     */
    public static HttpException unsupportedMediaType(String message) {
        return new HttpException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message);
    }

    /**
     * Creates a {@code 422 Unprocessable Content} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 422 status
     */
    public static HttpException unprocessable(String message) {
        return new HttpException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }

    /**
     * Creates a {@code 429 Too Many Requests} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 429 status
     */
    public static HttpException tooManyRequests(String message) {
        return new HttpException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    /**
     * Creates a {@code 503 Service Unavailable} exception.
     *
     * @param message error description
     * @return {@code HttpException} with 503 status
     */
    public static HttpException serviceUnavailable(String message) {
        return new HttpException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
