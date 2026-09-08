package io.waveio.http;

import java.util.Objects;

/** A validated origin-form HTTP request target. */
public record RequestUri(String path, String query) {
    /** Creates a URI from a raw origin-form request target without decoding it. */
    public static RequestUri parse(String target) {
        Objects.requireNonNull(target, "target");
        if (!target.startsWith("/") || target.indexOf('#') >= 0) { throw new IllegalArgumentException("request target must be origin-form"); }
        int queryAt = target.indexOf('?');
        return queryAt < 0 ? new RequestUri(target, "") : new RequestUri(target.substring(0, queryAt), target.substring(queryAt + 1));
    }
    /** Validates path and query components. */
    public RequestUri {
        if (path == null || path.isEmpty() || !path.startsWith("/")) { throw new IllegalArgumentException("path must be non-empty origin-form"); }
        query = Objects.requireNonNull(query, "query");
    }
}
