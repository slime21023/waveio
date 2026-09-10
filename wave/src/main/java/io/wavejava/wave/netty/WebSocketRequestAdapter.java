package io.wavejava.wave.netty;

import io.wavejava.wave.api.http.CancellationToken;
import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.RequestContext;
import io.wavejava.wave.api.websocket.WebSocketClientRequest;
import java.net.URI;
import java.util.UUID;

/** Builds the internal HTTP request used after a WebSocket handshake succeeds. */
final class WebSocketRequestAdapter {
    private WebSocketRequestAdapter() {
    }

    static Request toRequest(WebSocketClientRequest source, CancellationToken sessionCancellation) {
        var uri = source.uri();
        var requestHeaders = source.headers().toBuilder().set("Host", authority(uri)).build();
        var context = RequestContext.builder("wave-ws-client-" + UUID.randomUUID())
                .cancellationToken(sessionCancellation);
        source.deadline().ifPresent(context::deadline);
        return Request.builder()
                .method(HttpMethod.GET)
                .scheme("ws")
                .authority(authority(uri))
                .path(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                .rawQuery(uri.getRawQuery())
                .headers(requestHeaders)
                .context(context.build())
                .build();
    }

    static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 80 : uri.getPort();
    }

    private static String authority(URI uri) {
        var host = uri.getHost();
        var bracketed = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        var port = uri.getPort();
        return port < 0 || port == 80 ? bracketed : bracketed + ':' + port;
    }
}
