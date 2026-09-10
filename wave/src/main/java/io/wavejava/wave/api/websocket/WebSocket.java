package io.wavejava.wave.api.websocket;

import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import java.util.List;
import java.util.Objects;

/**
 * Application endpoint callback for one accepted WebSocket connection; it is not the live session
 * itself.
 *
 * <p>The endpoint callback is invoked only after the HTTP upgrade response has been written. It
 * is never invoked on a transport event loop. A WebSocket endpoint is registered through
 * {@code Routes.Builder.websocket(...)} or accepted explicitly from an ordinary
 * {@code void handle(Request, Response)} handler with {@link #accept(Request, Response, WebSocket)}.
 * The original HTTP request remains available from {@link WebSocketSession#request()}.</p>
 */
@FunctionalInterface
public interface WebSocket {
    /** Handles one successfully upgraded WebSocket session. */
    void handle(WebSocketSession session) throws Exception;

    /** Returns the finite transport budgets for this endpoint. */
    default WebSocketLimits limits() {
        return WebSocketLimits.defaults();
    }

    /**
     * Returns supported subprotocol tokens in preference order.
     *
     * <p>An empty list means that the server does not negotiate a subprotocol.</p>
     */
    default List<String> subprotocols() {
        return List.of();
    }

    /**
     * Marks an invocation-owned HTTP response as an accepted WebSocket upgrade.
     *
     * <p>This method intentionally only records an application decision. The HTTP/1.1 adapter
     * validates the wire handshake after normal middleware, routing, and exception mapping have
     * completed, so a malformed upgrade receives a deterministic HTTP error rather than a partial
     * session. In 0.5 an upgrade requires the server's aggregate request-body mode; a server
     * configured for streaming HTTP request bodies rejects the upgrade with HTTP {@code 400}
     * rather than risking ambiguity between HTTP content and WebSocket frames.</p>
     *
     * @return {@code response}, for direct handler control flow
     */
    static Response accept(Request request, Response response, WebSocket endpoint) {
        Objects.requireNonNull(request, "request");
        return Objects.requireNonNull(response, "response")
                .webSocket(Objects.requireNonNull(endpoint, "endpoint"));
    }

    /** Returns a view of {@code endpoint} with the supplied finite limits. */
    static WebSocket withLimits(WebSocket endpoint, WebSocketLimits limits) {
        var delegate = Objects.requireNonNull(endpoint, "endpoint");
        var configuredLimits = Objects.requireNonNull(limits, "limits");
        return new WebSocket() {
            @Override
            public void handle(WebSocketSession session) throws Exception {
                delegate.handle(session);
            }

            @Override
            public WebSocketLimits limits() {
                return configuredLimits;
            }

            @Override
            public List<String> subprotocols() {
                return delegate.subprotocols();
            }
        };
    }

    /** Returns a view of {@code endpoint} with explicit subprotocol preference order. */
    static WebSocket withSubprotocols(WebSocket endpoint, List<String> subprotocols) {
        var delegate = Objects.requireNonNull(endpoint, "endpoint");
        var configuredProtocols = WebSocketLimits.validateSubprotocols(subprotocols);
        return new WebSocket() {
            @Override
            public void handle(WebSocketSession session) throws Exception {
                delegate.handle(session);
            }

            @Override
            public WebSocketLimits limits() {
                return delegate.limits();
            }

            @Override
            public List<String> subprotocols() {
                return configuredProtocols;
            }
        };
    }
}
