package io.wavejava.wave.api.middleware;

import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.routing.RouteMatch;

/**
 * Hooks around one application request invocation.
 *
 * <p>Writing a response in {@link #onRequest(Request, Response)} or
 * {@link #onRoute(Request, RouteMatch, Response)} short-circuits later processing. Every middleware
 * whose request hook was entered is nevertheless notified through {@link #onResponse(Request,
 * Response, Outcome)} in reverse registration order.</p>
 */
public interface Middleware {
    /** Performs work before route matching and may commit a short-circuit response. */
    default void onRequest(Request request, Response response) throws Exception {
    }

    /** Performs work after a route match and may commit a short-circuit response. */
    default void onRoute(Request request, RouteMatch route, Response response) throws Exception {
    }

    /** Observes the final application outcome; failures here are logged and do not rewrite a response. */
    default void onResponse(Request request, Response response, Outcome outcome) {
    }
}
