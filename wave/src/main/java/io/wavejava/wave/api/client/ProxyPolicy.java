package io.wavejava.wave.api.client;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Immutable proxy-routing policy for a client transport. */
public final class ProxyPolicy {
    private static final ProxyPolicy DIRECT = new ProxyPolicy(null);

    private final URI endpoint;

    private ProxyPolicy(URI endpoint) {
        this.endpoint = endpoint;
    }

    /** Returns a policy that connects directly to request origins. */
    public static ProxyPolicy direct() {
        return DIRECT;
    }

    /**
     * Routes HTTP requests through an HTTP proxy endpoint.
     *
     * <p>The endpoint must be an absolute {@code http} URI containing only an authority. Proxy
     * credentials are intentionally not accepted in this core policy. The owned HTTP/1.1 transport
     * sends {@code http} targets in absolute form through this endpoint. When a {@link WaveClient}
     * enables HTTP/2, an {@code https} target is first established through one bounded HTTP
     * {@code CONNECT} tunnel and then performs TLS hostname verification and ALPN end-to-end with
     * the origin. An HTTP/1.1 fallback selected by ALPN retains the byte-client's existing HTTPS
     * proxy limitation, so applications that require a proxy tunnel must use
     * {@link io.wavejava.wave.api.server.Http2Config.Mode#REQUIRE} until a future HTTP/1.1 tunnel
     * transport is added.</p>
     */
    public static ProxyPolicy http(URI endpoint) {
        return new ProxyPolicy(validateEndpoint(endpoint));
    }

    /** Returns whether the client connects directly to origins. */
    public boolean isDirect() {
        return endpoint == null;
    }

    /** Returns the validated proxy endpoint when this policy is not direct. */
    public Optional<URI> endpoint() {
        return Optional.ofNullable(endpoint);
    }

    private static URI validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute() || !"http".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || (endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty() && !endpoint.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("HTTP proxy endpoint must be an authority-only http URI: " + endpoint);
        }
        if (endpoint.getPort() > 65_535) {
            throw new IllegalArgumentException("HTTP proxy port must be at most 65535: " + endpoint);
        }
        return URI.create(endpoint.toString());
    }
}
