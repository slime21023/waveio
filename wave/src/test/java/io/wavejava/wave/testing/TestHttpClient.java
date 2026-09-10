package io.wavejava.wave.testing;

import io.wavejava.wave.api.http.Headers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * A small blocking HTTP/1.1 client for exercising an {@link EmbeddedApp} over a real socket.
 *
 * <p>This is intentionally a test fixture rather than wave's future production HTTP client. It
 * supports only text GET and POST requests in 0.1, and turns I/O failures into assertion failures
 * so a test points directly at its failed exchange.</p>
 */
public final class TestHttpClient {
    private static final String DEFAULT_POST_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final URI baseUri;
    private final HttpClient client;

    /** Creates a client using a new JDK HTTP/1.1 client and the supplied base URI. */
    public TestHttpClient(URI baseUri) {
        this(baseUri, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
    }

    /**
     * Creates a client that resolves origin-form paths against {@code baseUri}.
     *
     * <p>The supplied JDK client is useful when a test needs a particular redirect or timeout
     * policy, while the helper's request and response contract remains unchanged.</p>
     */
    public TestHttpClient(URI baseUri, HttpClient client) {
        this.baseUri = validateBaseUri(baseUri);
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Returns the configured base URI, which always ends with a slash. */
    public URI baseUri() {
        return baseUri;
    }

    /** Sends a blocking HTTP GET for an origin-form {@code path}. */
    public Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    /** Sends a blocking UTF-8 text/plain HTTP POST for an origin-form {@code path}. */
    public Response post(String path, String body) {
        return post(path, DEFAULT_POST_CONTENT_TYPE, body);
    }

    /** Sends a blocking UTF-8 HTTP POST with the supplied {@code Content-Type}. */
    public Response post(String path, String contentType, String body) {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        var request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    /** Resolves one origin-form path against this client's loopback base URI. */
    public URI uri(String path) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("Test client paths must be origin-form paths beginning with one '/': " + path);
        }
        return baseUri.resolve(path);
    }

    private Response send(HttpRequest request) {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), toHeaders(response.headers()), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("HTTP test exchange was interrupted", interrupted);
        } catch (IOException failure) {
            throw new AssertionError("HTTP test exchange failed: " + request.method() + ' ' + request.uri(), failure);
        }
    }

    private static Headers toHeaders(HttpHeaders headers) {
        var result = Headers.builder();
        headers.map().forEach((name, values) -> values.forEach(value -> result.add(name, value)));
        return result.build();
    }

    private static URI validateBaseUri(URI baseUri) {
        Objects.requireNonNull(baseUri, "baseUri");
        if (!baseUri.isAbsolute() || baseUri.getHost() == null || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("Test client base URI must be an absolute authority URI without query or fragment: "
                    + baseUri);
        }
        return URI.create(baseUri.toString().endsWith("/") ? baseUri.toString() : baseUri + "/");
    }

    /** Immutable response snapshot returned by a blocking test exchange. */
    public record Response(int statusCode, Headers headers, String body) {
        public Response {
            if (statusCode < 100 || statusCode > 999) {
                throw new IllegalArgumentException("Invalid HTTP response status: " + statusCode);
            }
            headers = Objects.requireNonNull(headers, "headers");
            body = Objects.requireNonNull(body, "body");
        }

        /** Alias for {@link #statusCode()} matching wave's server-side response API. */
        public int status() {
            return statusCode;
        }

        /** Returns the first response header value for {@code name}, if present. */
        public Optional<String> header(String name) {
            return headers.first(name);
        }
    }
}

