package io.wavejava.wave.api.http;

import java.util.Objects;

/** An application exception that intentionally maps to a problem response. */
public final class HttpException extends RuntimeException {
    private final Problem problem;

    public HttpException(Problem problem) {
        super(messageFor(problem));
        this.problem = Objects.requireNonNull(problem, "problem");
    }

    public HttpException(Problem problem, Throwable cause) {
        super(messageFor(problem), cause);
        this.problem = Objects.requireNonNull(problem, "problem");
    }

    /** Returns the problem that should be rendered by an exception mapper. */
    public Problem problem() {
        return problem;
    }

    /** Returns {@code problem().status()}. */
    public int status() {
        return problem.status();
    }

    public static HttpException of(int status, String title) {
        return new HttpException(Problem.of(status, title));
    }

    public static HttpException badRequest(String detail) {
        return new HttpException(Problem.of(400, "Bad Request").withDetail(detail));
    }

    public static HttpException unauthorized(String detail) {
        return new HttpException(Problem.of(401, "Unauthorized").withDetail(detail));
    }

    public static HttpException forbidden(String detail) {
        return new HttpException(Problem.of(403, "Forbidden").withDetail(detail));
    }

    public static HttpException notFound(String detail) {
        return new HttpException(Problem.of(404, "Not Found").withDetail(detail));
    }

    public static HttpException conflict(String detail) {
        return new HttpException(Problem.of(409, "Conflict").withDetail(detail));
    }

    public static HttpException unprocessableEntity(String detail) {
        return new HttpException(Problem.of(422, "Unprocessable Content").withDetail(detail));
    }

    private static String messageFor(Problem problem) {
        Objects.requireNonNull(problem, "problem");
        return problem.detail().orElse(problem.title());
    }
}
