package io.wavejava.wave.runtime;

import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.middleware.Outcome;
import java.util.Objects;

/** Internal immutable result produced by an application dispatch before a transport writes a response. */
public record ApplicationResult(Response response, Outcome outcome, String routePattern) {
    public ApplicationResult {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(routePattern, "routePattern");
    }
}

