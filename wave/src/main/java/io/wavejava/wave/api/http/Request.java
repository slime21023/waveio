package io.wavejava.wave.api.http;

import io.wavejava.wave.api.http.BodyPublisher;
import io.wavejava.wave.api.http.PublicAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable view of an HTTP request.
 *
 * <p>Metadata, headers, query values, and route path parameters are immutable. The associated
 * selected body representation deliberately remains single-consumption state owned by this
 * request invocation.</p>
 */
public final class Request {
    private final HttpMethod method;
    private final HttpVersion version;
    private final String scheme;
    private final String authority;
    private final PublicAddress publicAddress;
    private final String path;
    private final String rawQuery;
    private final Headers headers;
    private final Body body;
    private final BodyPublisher bodyPublisher;
    private final SocketAddress remoteAddress;
    private final RequestContext context;
    private final Map<String, List<String>> queryParameters;
    private final Map<String, String> pathParameters;
    private final List<Cookie> cookies;

    private Request(Builder builder, Map<String, String> pathParameters) {
        method = Objects.requireNonNull(builder.method, "method");
        version = Objects.requireNonNull(builder.version, "version");
        scheme = normalizeScheme(builder.scheme);
        path = validatePath(builder.path);
        rawQuery = builder.rawQuery;
        headers = Objects.requireNonNull(builder.headers, "headers");
        if (builder.body != null && builder.bodyPublisher != null) {
            throw new IllegalStateException("A request cannot have both an aggregated and streaming body");
        }
        body = builder.bodyPublisher == null ? (builder.body == null ? Body.empty() : builder.body) : null;
        bodyPublisher = builder.bodyPublisher == null ? null : BodyPublisher.singleUse(builder.bodyPublisher);
        authority = builder.authority == null ? headers.first("Host").orElse("") : validateAuthority(builder.authority);
        publicAddress = builder.publicAddress == null
                ? PublicAddress.parse(scheme, authority).orElse(null)
                : Objects.requireNonNull(builder.publicAddress, "publicAddress");
        remoteAddress = builder.remoteAddress;
        context = builder.context;
        queryParameters = parseQuery(rawQuery);
        this.pathParameters = immutablePathParameters(pathParameters);
        cookies = parseCookies(headers);
    }

    /** Creates a request builder with method {@code GET}, HTTP/1.1, and path {@code /}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a basic request for tests and in-memory invocation. */
    public static Request of(HttpMethod method, String path) {
        return builder().method(method).path(path).build();
    }

    public HttpMethod method() {
        return method;
    }

    public HttpVersion version() {
        return version;
    }

    public String scheme() {
        return scheme;
    }

    /** Returns the authority from the request target or Host header, or an empty string if absent. */
    public String authority() {
        return authority;
    }

    /**
     * Returns the validated public origin selected by the transport/trusted-proxy policy.
     *
     * <p>For an ordinary direct request this is derived from the HTTP scheme and authority when
     * they form a valid HTTP origin. It is empty for fixtures or malformed/absent authority; an
     * untrusted peer can never set it through forwarding headers.</p>
     */
    public Optional<PublicAddress> publicAddress() {
        return Optional.ofNullable(publicAddress);
    }

    /** Returns the origin-form path without its query string. */
    public String path() {
        return path;
    }

    /** Returns the unmodified origin-form path. In 0.1 this is equivalent to {@link #path()}. */
    public String rawPath() {
        return path;
    }

    /** Returns the raw query string without a leading {@code ?}, if present. */
    public Optional<String> rawQuery() {
        return Optional.ofNullable(rawQuery);
    }

    /** Returns a complete origin-form request target. */
    public String target() {
        return rawQuery == null ? path : path + '?' + rawQuery;
    }

    public Headers headers() {
        return headers;
    }

    /** Returns the first header value for {@code name}. */
    public Optional<String> header(String name) {
        return headers.first(name);
    }

    /**
     * Returns this invocation's bounded, single-consumption aggregate request body.
     *
     * @throws IllegalStateException when this request was built with a streaming body publisher
     */
    public Body body() {
        if (body == null) {
            throw new IllegalStateException("Request has a streaming body; use bodyPublisher() instead");
        }
        return body;
    }

    /**
     * Returns this request's optional streaming body source.
     *
     * <p>A non-empty result and {@link #body()} are mutually exclusive. HTTP/1.1 uses the
     * aggregate body path by default; a server explicitly configured for streaming request
     * bodies exposes this source after the request head has been accepted.</p>
     */
    public Optional<BodyPublisher> bodyPublisher() {
        return Optional.ofNullable(bodyPublisher);
    }

    /**
     * Returns the streaming body source.
     *
     * @throws IllegalStateException when this request carries an aggregate body
     */
    public BodyPublisher streamingBody() {
        return bodyPublisher().orElseThrow(
                () -> new IllegalStateException("Request has an aggregated body; use body() instead"));
    }

    /** Returns whether this request was built with a Flow streaming body. */
    public boolean hasStreamingBody() {
        return bodyPublisher != null;
    }

    /** Returns the peer address when supplied by the transport. */
    public Optional<SocketAddress> remoteAddress() {
        return Optional.ofNullable(remoteAddress);
    }

    /**
     * Returns execution metadata when this request is being invoked by a server runtime.
     *
     * <p>Requests built for fixtures or in-memory use have no context by default.</p>
     */
    public Optional<RequestContext> context() {
        return Optional.ofNullable(context);
    }

    /** Returns this request's deadline when its execution context defines one. */
    public Optional<Deadline> deadline() {
        return context == null ? Optional.empty() : context.deadline();
    }

    /** Returns this request's cancellation signal when an execution context is present. */
    public Optional<CancellationToken> cancellationToken() {
        return context == null ? Optional.empty() : Optional.of(context.cancellationToken());
    }

    /** Returns decoded query values in wire order. */
    public Map<String, List<String>> queryParameters() {
        return queryParameters;
    }

    /** Returns all decoded values for one query name. */
    public List<String> queryParameters(String name) {
        return queryParameters.getOrDefault(Objects.requireNonNull(name, "name"), List.of());
    }

    /** Returns cookies from all request {@code Cookie} headers in wire order. */
    public List<Cookie> cookies() {
        return cookies;
    }

    /** Returns the first request cookie named {@code name}, if present. */
    public Optional<Cookie> cookie(String name) {
        Objects.requireNonNull(name, "name");
        return cookies.stream().filter(cookie -> cookie.name().equals(name)).findFirst();
    }

    /** Returns route-bound path parameters. */
    public Map<String, String> pathParameters() {
        return pathParameters;
    }

    /** Returns a route-bound path parameter, if present. */
    public Optional<String> pathParameter(String name) {
        return Optional.ofNullable(pathParameters.get(Objects.requireNonNull(name, "name")));
    }

    /**
     * Returns a request with the supplied immutable route path parameters.
     *
     * <p>The request body is intentionally shared: route binding must not create a second readable
     * body stream for the same invocation.</p>
     */
    public Request withPathParameters(Map<String, String> pathParameters) {
        return new Request(toBuilder(), pathParameters);
    }

    /**
     * Returns this request associated with {@code context}.
     *
     * <p>The request body is intentionally shared because attaching execution metadata does not
     * create a new request invocation or a second readable body.</p>
     */
    public Request withContext(RequestContext context) {
        return new Request(toBuilder().context(context), pathParameters);
    }

    /** Returns this request with a transport-selected public origin while preserving wire metadata. */
    public Request withPublicAddress(PublicAddress publicAddress) {
        return new Request(toBuilder().publicAddress(publicAddress), pathParameters);
    }

    private Builder toBuilder() {
        var builder = new Builder()
                .method(method)
                .version(version)
                .scheme(scheme)
                .authority(authority)
                .path(path)
                .rawQuery(rawQuery)
                .headers(headers);
        if (publicAddress != null) {
            builder.publicAddress(publicAddress);
        }
        if (bodyPublisher != null) {
            builder.bodyPublisher(bodyPublisher);
        } else {
            builder.body(body);
        }
        if (remoteAddress != null) {
            builder.remoteAddress(remoteAddress);
        }
        if (context != null) {
            builder.context(context);
        }
        return builder;
    }

    private static String normalizeScheme(String scheme) {
        Objects.requireNonNull(scheme, "scheme");
        if (scheme.isBlank()) {
            throw new IllegalArgumentException("Request scheme must not be blank");
        }
        for (var index = 0; index < scheme.length(); index++) {
            var character = scheme.charAt(index);
            if (!(character >= 'a' && character <= 'z') && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9') && character != '+' && character != '-' && character != '.') {
                throw new IllegalArgumentException("Invalid request scheme: " + scheme);
            }
        }
        return scheme.toLowerCase(java.util.Locale.ROOT);
    }

    private static String validateAuthority(String authority) {
        Objects.requireNonNull(authority, "authority");
        Headers.validateValue(authority);
        return authority;
    }

    private static String validatePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException("Request path must be a non-empty origin-form path beginning with '/'");
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Request path must not contain a query, fragment, or line break");
        }
        return path;
    }

    private static Map<String, List<String>> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, List<String>>();
        for (var pair : rawQuery.split("&", -1)) {
            var separator = pair.indexOf('=');
            var rawName = separator < 0 ? pair : pair.substring(0, separator);
            var rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            var name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            var value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        var immutable = new LinkedHashMap<String, List<String>>();
        result.forEach((name, values) -> immutable.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(immutable);
    }

    private static Map<String, String> immutablePathParameters(Map<String, String> pathParameters) {
        Objects.requireNonNull(pathParameters, "pathParameters");
        var copy = new LinkedHashMap<String, String>();
        pathParameters.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Path parameter name must not be blank");
            }
            copy.put(name, Objects.requireNonNull(value, "path parameter value"));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static List<Cookie> parseCookies(Headers headers) {
        var cookies = new ArrayList<Cookie>();
        headers.all("Cookie").forEach(header -> cookies.addAll(Cookie.parseRequestHeader(header)));
        return List.copyOf(cookies);
    }

    /** Builder for {@link Request}. A builder creates one request invocation. */
    public static final class Builder {
        private HttpMethod method = HttpMethod.GET;
        private HttpVersion version = HttpVersion.HTTP_1_1;
        private String scheme = "http";
        private String authority;
        private PublicAddress publicAddress;
        private String path = "/";
        private String rawQuery;
        private Headers headers = Headers.empty();
        private Body body;
        private BodyPublisher bodyPublisher;
        private SocketAddress remoteAddress;
        private RequestContext context;

        public Builder method(HttpMethod method) {
            this.method = Objects.requireNonNull(method, "method");
            return this;
        }

        public Builder method(String method) {
            return method(HttpMethod.of(method));
        }

        public Builder version(HttpVersion version) {
            this.version = Objects.requireNonNull(version, "version");
            return this;
        }

        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        public Builder authority(String authority) {
            this.authority = authority;
            return this;
        }

        /** Selects a validated public origin independent of wire scheme and authority. */
        public Builder publicAddress(PublicAddress publicAddress) {
            this.publicAddress = Objects.requireNonNull(publicAddress, "publicAddress");
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /** Sets the raw query string; a leading {@code ?} is accepted and removed. */
        public Builder rawQuery(String rawQuery) {
            if (rawQuery != null && rawQuery.startsWith("?")) {
                rawQuery = rawQuery.substring(1);
            }
            if (rawQuery != null && (rawQuery.indexOf('#') >= 0 || rawQuery.indexOf('\r') >= 0 || rawQuery.indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("Request query must not contain a fragment or line break");
            }
            this.rawQuery = rawQuery;
            return this;
        }

        /** Parses an origin-form target containing a path and optional query string. */
        public Builder target(String target) {
            Objects.requireNonNull(target, "target");
            var separator = target.indexOf('?');
            if (separator < 0) {
                return path(target);
            }
            return path(target.substring(0, separator)).rawQuery(target.substring(separator + 1));
        }

        public Builder headers(Headers headers) {
            this.headers = Objects.requireNonNull(headers, "headers");
            return this;
        }

        public Builder header(String name, String value) {
            headers = headers.toBuilder().set(name, value).build();
            return this;
        }

        public Builder addHeader(String name, String value) {
            headers = headers.toBuilder().add(name, value).build();
            return this;
        }

        public Builder body(Body body) {
            if (bodyPublisher != null) {
                throw new IllegalStateException("A request body publisher has already been selected");
            }
            this.body = Objects.requireNonNull(body, "body");
            return this;
        }

        /**
         * Selects a single-consumption Flow request body instead of an aggregate {@link Body}.
         *
         * @throws IllegalStateException when an aggregate body has already been selected
         */
        public Builder bodyPublisher(BodyPublisher bodyPublisher) {
            if (body != null) {
                throw new IllegalStateException("An aggregated request body has already been selected");
            }
            this.bodyPublisher = Objects.requireNonNull(bodyPublisher, "bodyPublisher");
            return this;
        }

        public Builder remoteAddress(SocketAddress remoteAddress) {
            this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
            return this;
        }

        /** Associates optional execution metadata with the request being built. */
        public Builder context(RequestContext context) {
            this.context = Objects.requireNonNull(context, "context");
            return this;
        }

        public Request build() {
            return new Request(this, Map.of());
        }
    }
}
