package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.body.RequestBody;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpRequestTest {

    private static final AttributeKey<String> ATTR_USER = AttributeKey.of("user");
    private static final AttributeKey<Integer> ATTR_COUNT = AttributeKey.of("count");

    @Test
    void accessorsReturnConfiguredValues() {
        var remote = new InetSocketAddress("127.0.0.1", 12345);
        var headers = HttpHeaders.builder().add("host", "localhost").build();
        var body = RequestBody.of("payload".getBytes());
        var query = Map.of("tag", List.of("a", "b"), "empty", List.<String>of());
        var pathParams = Map.of("id", "42");

        var request = new HttpRequest(HttpMethod.POST, "/items/42", headers, body,
                remote, query, pathParams);

        assertEquals(HttpMethod.POST, request.method());
        assertEquals("/items/42", request.path());
        assertEquals(headers, request.headers());
        assertEquals("payload", request.body().text());
        assertEquals(remote, request.remoteAddress());
        assertEquals(pathParams, request.pathParameters());

        assertEquals("42", request.pathParam("id").orElseThrow());
        assertFalse(request.pathParam("missing").isPresent());
        assertEquals("42", request.requirePathParam("id"));
        assertThrows(IllegalArgumentException.class, () -> request.requirePathParam("missing"));

        assertEquals(List.of("a", "b"), request.queryParams("tag"));
        assertEquals("a", request.queryParam("tag").orElseThrow());
        assertTrue(request.queryParams("missing").isEmpty());
        assertFalse(request.queryParam("missing").isPresent());
        assertFalse(request.queryParam("empty").isPresent());
    }

    @Test
    void parsesCookiesWithVariousFormats() {
        var headers = HttpHeaders.builder()
                .add("Cookie", "session=\"xyz-123\"; theme=dark; =emptyName; noEquals; id=first")
                .add("Cookie", "id=second; quoted=\"\"; singleQuote=\"; special=\"a=b\"; startsOnly=\"abc; endsOnly=abc\"")
                .build();

        var request = new HttpRequest(HttpMethod.GET, "/", headers, RequestBody.empty(),
                null, Map.of(), Map.of());

        var cookies = request.cookies();
        assertEquals("xyz-123", cookies.get("session"));
        assertEquals("dark", cookies.get("theme"));
        assertEquals("first", cookies.get("id")); // putIfAbsent ignores second
        assertEquals("", cookies.get("quoted"));
        assertEquals("\"", cookies.get("singleQuote"));
        assertEquals("a=b", cookies.get("special"));
        assertEquals("\"abc", cookies.get("startsOnly"));
        assertEquals("abc\"", cookies.get("endsOnly"));
        assertNull(cookies.get("noEquals"));
        assertNull(cookies.get(""));

        assertEquals("xyz-123", request.cookie("session").orElseThrow());
        assertFalse(request.cookie("missing").isPresent());
    }

    @Test
    void attributesAreStoredAndPreservedAcrossWithPathParameters() {
        var request = new HttpRequest(HttpMethod.GET, "/test", HttpHeaders.empty(),
                RequestBody.empty(), null, Map.of(), Map.of());

        assertFalse(request.attribute(ATTR_USER).isPresent());

        request.setAttribute(ATTR_USER, "alice");
        request.setAttribute(ATTR_COUNT, 42);

        assertEquals("alice", request.attribute(ATTR_USER).orElseThrow());
        assertEquals(42, request.attribute(ATTR_COUNT).orElseThrow());

        var updated = request.withPathParameters(Map.of("key", "val"));
        assertEquals("val", updated.pathParam("key").orElseThrow());
        assertEquals("alice", updated.attribute(ATTR_USER).orElseThrow());
        assertEquals(42, updated.attribute(ATTR_COUNT).orElseThrow());
    }

    @Test
    void readsTypedPathAndQueryParameters() {
        var request = new HttpRequest(HttpMethod.GET, "/items/42", HttpHeaders.empty(),
                RequestBody.empty(), null,
                Map.of("page", List.of("3"), "size", List.of("9007199254740993"),
                        "verbose", List.of("true")),
                Map.of("id", "42", "big", "9007199254740993"));

        assertEquals(42, request.requireIntPathParam("id"));
        assertEquals(9007199254740993L, request.requireLongPathParam("big"));
        assertEquals(3, request.intQueryParam("page").orElseThrow());
        assertEquals(9007199254740993L, request.longQueryParam("size").orElseThrow());
        assertTrue(request.booleanQueryParam("verbose").orElseThrow());
        assertTrue(request.intQueryParam("absent").isEmpty());
    }

    @Test
    void malformedParameterValueIsAClientError() {
        var request = new HttpRequest(HttpMethod.GET, "/items/abc", HttpHeaders.empty(),
                RequestBody.empty(), null, Map.of("page", List.of("x")),
                Map.of("id", "abc"));

        var path = assertThrows(HttpException.class, () -> request.requireIntPathParam("id"));
        assertEquals(HttpStatus.BAD_REQUEST, path.status());

        var query = assertThrows(HttpException.class, () -> request.intQueryParam("page"));
        assertEquals(HttpStatus.BAD_REQUEST, query.status());
    }

    @Test
    void missingParameterRemainsAProgrammingError() {
        var request = new HttpRequest(HttpMethod.GET, "/items", HttpHeaders.empty(),
                RequestBody.empty(), null, Map.of(), Map.of());

        assertThrows(IllegalArgumentException.class, () -> request.requirePathParam("id"));
        assertThrows(IllegalArgumentException.class, () -> request.requireIntPathParam("id"));
    }

    @Test
    void decodesTheBodyWithTheAttachedCodec() {
        var request = requestWithBody("widget:3").withCodec(new UpperCodec());

        assertEquals("WIDGET:3", request.bodyAs(String.class));
    }

    @Test
    void aMalformedBodyIsAClientErrorNotAServerError() {
        var failing = new io.waveio.http.body.BodyCodec() {
            @Override public String contentType() { return "text/plain"; }
            @Override public byte[] encode(Object value) { return new byte[0]; }
            @Override public <T> T decode(byte[] body, Class<T> type) {
                throw new IllegalArgumentException("not parseable");
            }
        };
        var request = requestWithBody("garbage").withCodec(failing);

        var failure = assertThrows(HttpException.class, () -> request.bodyAs(String.class));
        assertEquals(HttpStatus.BAD_REQUEST, failure.status());
    }

    @Test
    void decodingWithoutAConfiguredCodecIsAServerMisconfiguration() {
        var request = requestWithBody("widget:3");

        assertThrows(IllegalStateException.class, () -> request.bodyAs(String.class));
    }

    @Test
    void copiesPreserveTheAttachedCodec() {
        var request = requestWithBody("widget:3").withCodec(new UpperCodec());

        assertEquals("WIDGET:3", request.withPathParameters(Map.of("id", "1")).bodyAs(String.class));
        assertEquals("OTHER",
                request.withBody(RequestBody.of("other".getBytes())).bodyAs(String.class));
    }

    private static HttpRequest requestWithBody(String body) {
        return new HttpRequest(HttpMethod.POST, "/items", HttpHeaders.empty(),
                RequestBody.of(body.getBytes()), null, Map.of(), Map.of());
    }

    private static final class UpperCodec implements io.waveio.http.body.BodyCodec {
        @Override public String contentType() { return "text/plain"; }
        @Override public byte[] encode(Object value) { return value.toString().getBytes(); }
        @Override public <T> T decode(byte[] body, Class<T> type) {
            return type.cast(new String(body).toUpperCase(java.util.Locale.ROOT));
        }
    }
}
