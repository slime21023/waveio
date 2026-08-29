package io.waveio.http;

import io.waveio.http.body.BodyCodec;
import io.waveio.http.body.RequestBody;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable model of an incoming HTTP request.
 *
 * <p>Encapsulates all metadata and payload components received from the HTTP client:
 * <ul>
 *   <li>{@link #method()}: HTTP verb (e.g., GET, POST).</li>
 *   <li>{@link #path()}: Normalized, percent-decoded request path.</li>
 *   <li>{@link #headers()}: Case-insensitive header collection.</li>
 *   <li>{@link #body()}: In-memory request payload bytes.</li>
 *   <li>{@link #remoteAddress()}: Client socket network address.</li>
 *   <li>{@link #pathParam(String)} / {@link #requirePathParam(String)}: Dynamic path variables.</li>
 *   <li>{@link #queryParam(String)} / {@link #queryParams(String)}: Query parameters.</li>
 *   <li>{@link #cookie(String)} / {@link #cookies()}: Request cookies (lazily parsed).</li>
 *   <li>{@link #attribute(AttributeKey)}: Type-safe request-scoped context container.</li>
 * </ul>
 *
 * <p><b>Concurrency &amp; Mutability:</b> All fields except the internal {@code attributes} map
 * are deeply immutable. The attributes map is backed by a {@link ConcurrentHashMap} and is safe
 * for concurrent mutation across middleware and handlers.
 */
public final class HttpRequest {
    private final HttpMethod method;
    private final String path;
    private final HttpHeaders headers;
    private final RequestBody body;
    private final InetSocketAddress remoteAddress;
    private final Map<String, List<String>> queryParameters;
    private final Map<String, String> pathParameters;
    private final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
    private BodyCodec codec;
    private volatile Map<String, String> cookies;

    /**
     * Constructs a new HTTP request model.
     *
     * @param method HTTP method
     * @param path decoded request path
     * @param headers HTTP headers
     * @param body request body bytes
     * @param remoteAddress client socket address
     * @param queryParameters query parameter map
     * @param pathParameters extracted route path parameters
     */
    public HttpRequest(HttpMethod method, String path, HttpHeaders headers, RequestBody body,
            InetSocketAddress remoteAddress, Map<String, List<String>> queryParameters,
            Map<String, String> pathParameters) {
        this.method = method;
        this.path = path;
        this.headers = headers;
        this.body = body;
        this.remoteAddress = remoteAddress;
        this.queryParameters = Map.copyOf(queryParameters);
        this.pathParameters = Map.copyOf(pathParameters);
    }

    /**
     * Returns the HTTP method of the request.
     *
     * @return HTTP method
     */
    public HttpMethod method() { return method; }

    /**
     * Returns the normalized, percent-decoded request path.
     *
     * @return request path string
     */
    public String path() { return path; }

    /**
     * Returns the HTTP request headers.
     *
     * @return immutable headers collection
     */
    public HttpHeaders headers() { return headers; }

    /**
     * Returns the raw in-memory request body.
     *
     * @return request body
     */
    public RequestBody body() { return body; }

    /**
     * Decodes the request body with the server's {@code BodyCodec}.
     *
     * <p>A body the codec cannot read is a client error and throws {@link HttpException} with
     * {@code 400}. Calling this without a configured codec is a server misconfiguration and throws
     * {@link IllegalStateException}, which maps to {@code 500}.
     *
     * @param <T> target type
     * @param type target class to deserialize into
     * @return decoded object instance
     * @throws HttpException with 400 if decoding fails
     * @throws IllegalStateException if no {@link BodyCodec} is configured on the server
     */
    public <T> T bodyAs(Class<T> type) {
        java.util.Objects.requireNonNull(type, "type");
        if (codec == null) {
            throw new IllegalStateException("No BodyCodec is configured; call "
                    + "HttpServerBuilder.bodyCodec(...) before using HttpRequest.bodyAs(...)");
        }
        try {
            return codec.decode(body.bytes(), type);
        } catch (Exception failure) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Request body is not a valid " + type.getSimpleName(), failure);
        }
    }

    /**
     * Returns the remote client's socket address.
     *
     * @return client socket address
     */
    public InetSocketAddress remoteAddress() { return remoteAddress; }

    /**
     * Returns an unmodifiable map of all extracted path parameters.
     *
     * @return map of path parameter names to values
     */
    public Map<String, String> pathParameters() { return pathParameters; }

    /**
     * Returns the value of a path parameter, if present.
     *
     * @param name parameter name defined in the route pattern (e.g. {@code "id"})
     * @return optional containing the parameter value, or empty if not present
     */
    public Optional<String> pathParam(String name) { return Optional.ofNullable(pathParameters.get(name)); }

    /**
     * Returns the value of a required path parameter, or throws {@link IllegalArgumentException}.
     *
     * @param name parameter name
     * @return non-null parameter value
     * @throws IllegalArgumentException if the parameter was not matched by the route
     */
    public String requirePathParam(String name) {
        var value = pathParameters.get(name);
        if (value == null) throw new IllegalArgumentException("Missing path parameter: " + name);
        return value;
    }

    /**
     * Returns all values for a query parameter.
     *
     * @param name query parameter name
     * @return list of parameter values, or an empty list if absent
     */
    public List<String> queryParams(String name) { return queryParameters.getOrDefault(name, List.of()); }

    /**
     * Returns the first value of a query parameter, if present.
     *
     * @param name query parameter name
     * @return optional containing the first query value, or empty if absent
     */
    public Optional<String> queryParam(String name) {
        var values = queryParams(name);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    /**
     * Reads a declared path parameter as an {@code int}.
     *
     * <p>A missing parameter means the route pattern never declared it, which is a programming
     * error and throws {@link IllegalArgumentException}. A value that is present but not a number
     * is a client error and throws {@link HttpException} with {@code 400}.
     */
    public int requireIntPathParam(String name) {
        return (int) parseLong(name, requirePathParam(name), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** Long-valued counterpart of {@link #requireIntPathParam(String)}. */
    public long requireLongPathParam(String name) {
        return parseLong(name, requirePathParam(name), Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * Reads an optional query parameter as an {@code int}. An absent parameter yields an empty
     * result; a malformed one throws {@link HttpException} with {@code 400}.
     */
    public Optional<Integer> intQueryParam(String name) {
        return queryParam(name)
                .map(value -> (int) parseLong(name, value, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    /** Long-valued counterpart of {@link #intQueryParam(String)}. */
    public Optional<Long> longQueryParam(String name) {
        return queryParam(name).map(value -> parseLong(name, value, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    /**
     * Reads an optional query parameter as a boolean. Only {@code true} and {@code false},
     * ignoring case, are accepted; anything else throws {@link HttpException} with {@code 400}.
     */
    public Optional<Boolean> booleanQueryParam(String name) {
        return queryParam(name).map(value -> {
            if (value.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (value.equalsIgnoreCase("false")) return Boolean.FALSE;
            throw HttpException.badRequest(
                    "Parameter '" + name + "' must be true or false: " + value);
        });
    }

    private static long parseLong(String name, String value, long minimum, long maximum) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Parameter '" + name + "' must be a number: " + value, failure);
        }
        if (parsed < minimum || parsed > maximum) {
            throw HttpException.badRequest("Parameter '" + name + "' is out of range: " + value);
        }
        return parsed;
    }

    /**
     * Returns the value of a cookie sent by the client in the {@code Cookie} header.
     *
     * @param name cookie name
     * @return optional containing the cookie value, or empty if not present
     */
    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies().get(name));
    }

    /**
     * Returns an unmodifiable map of all cookies sent with the request, lazily parsed and cached.
     *
     * @return map of cookie names to unquoted values
     */
    public Map<String, String> cookies() {
        var result = cookies;
        if (result == null) {
            result = parseCookies();
            cookies = result;
        }
        return result;
    }

    private Map<String, String> parseCookies() {
        var parsed = new LinkedHashMap<String, String>();
        for (var header : headers.all("cookie")) {
            for (var entry : header.split(";")) {
                int separator = entry.indexOf('=');
                if (separator <= 0) continue;
                String name = entry.substring(0, separator).trim();
                String value = entry.substring(separator + 1).trim();
                if (!name.isEmpty()) parsed.putIfAbsent(name, unquote(value));
            }
        }
        return Map.copyOf(parsed);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Stores a request-scoped attribute value associated with a type-safe {@link AttributeKey}.
     *
     * @param <T> value type
     * @param key typed attribute key
     * @param value non-null attribute value
     */
    public <T> void setAttribute(AttributeKey<T> key, T value) {
        attributes.put(key, value);
    }

    /**
     * Retrieves a request-scoped attribute previously stored with {@link #setAttribute(AttributeKey, Object)}.
     *
     * @param <T> value type
     * @param key typed attribute key
     * @return optional containing the stored value, or empty if absent
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> attribute(AttributeKey<T> key) {
        return Optional.ofNullable((T) attributes.get(key));
    }

    /**
     * Returns a copy of this request with the specified path parameters.
     *
     * @param parameters extracted route path parameters
     * @return new {@code HttpRequest} instance
     */
    public HttpRequest withPathParameters(Map<String, String> parameters) {
        return copyWith(body, parameters);
    }

    /**
     * Returns a copy of this request with a replacement request body.
     *
     * @param value new request body
     * @return new {@code HttpRequest} instance
     */
    public HttpRequest withBody(RequestBody value) {
        return copyWith(java.util.Objects.requireNonNull(value, "body"), pathParameters);
    }

    /**
     * Attaches the server's body codec; the transport applies this when adapting a request.
     *
     * @param value configured body codec
     * @return new {@code HttpRequest} instance with codec attached
     */
    public HttpRequest withCodec(BodyCodec value) {
        var request = copyWith(body, pathParameters);
        request.codec = value;
        return request;
    }

    private HttpRequest copyWith(RequestBody newBody, Map<String, String> newPathParameters) {
        var request = new HttpRequest(method, path, headers, newBody, remoteAddress,
                queryParameters, newPathParameters);
        request.attributes.putAll(attributes);
        request.codec = codec;
        request.cookies = cookies;
        return request;
    }
}
