package io.wavejava.wave.api.routing;

import io.wavejava.wave.api.http.HttpMethod;
import java.util.Objects;

/** Immutable descriptive data for one registered route. */
public final class RouteMetadata {
    private final HttpMethod method;
    private final String pattern;

    RouteMetadata(HttpMethod method, String pattern) {
        this.method = Objects.requireNonNull(method, "method");
        this.pattern = Objects.requireNonNull(pattern, "pattern");
    }

    /** Returns the HTTP method registered for this route. */
    public HttpMethod method() {
        return method;
    }

    /** Returns the canonical path pattern registered for this route. */
    public String pattern() {
        return pattern;
    }

    /** Returns the canonical path pattern registered for this route. */
    public String pathPattern() {
        return pattern;
    }

    @Override
    public String toString() {
        return method.name() + " " + pattern;
    }
}
