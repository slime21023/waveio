package io.wavejava.wave.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.http.Response;
import io.wavejava.wave.api.http.ResponseState;
import io.wavejava.wave.api.middleware.Outcome;
import io.wavejava.wave.api.websocket.WebSocket;
import io.wavejava.wave.internal.http.ResponseData;
import io.wavejava.wave.internal.http.ResponseDataReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * An immutable HTTP/1.1 wire representation prepared away from the Netty event loop.
 *
 * <p>JSON encoding and response-header snapshots happen before this value reaches the transport.
 * The event loop only turns the prepared bytes into Netty buffers and writes them in connection
 * sequence order. The retained-byte estimate includes response metadata so the HTTP/1.1 pending
 * response budget accounts for every object held behind a slower predecessor.</p>
 */
final class PreparedResponse {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpVersion version;
    private final boolean headRequest;
    private final boolean keepAlive;
    private final int status;
    private final Headers headers;
    private final byte[] payload;
    private final Flow.Publisher<ByteBuffer> stream;
    private final WebSocket webSocket;
    private final Request webSocketRequest;
    private final long retainedBytes;
    private final Response applicationResponse;
    private final Outcome applicationOutcome;
    private final String routePattern;

    private PreparedResponse(
            HttpVersion version,
            boolean headRequest,
            boolean keepAlive,
            int status,
            Headers headers,
            byte[] payload,
            Flow.Publisher<ByteBuffer> stream,
            WebSocket webSocket,
            Request webSocketRequest,
            Response applicationResponse,
            Outcome applicationOutcome,
            String routePattern) {
        this.version = Objects.requireNonNull(version, "version");
        this.headRequest = headRequest;
        this.keepAlive = keepAlive;
        this.status = status;
        this.headers = Objects.requireNonNull(headers, "headers");
        var representations = (payload == null ? 0 : 1) + (stream == null ? 0 : 1) + (webSocket == null ? 0 : 1);
        if (representations != 1) {
            throw new IllegalArgumentException("prepared response must contain exactly one payload representation");
        }
        if ((webSocket == null) != (webSocketRequest == null)) {
            throw new IllegalArgumentException("a WebSocket response must retain both endpoint and request");
        }
        this.payload = payload;
        this.stream = stream;
        this.webSocket = webSocket;
        this.webSocketRequest = webSocketRequest;
        this.applicationResponse = applicationResponse;
        this.applicationOutcome = Objects.requireNonNull(applicationOutcome, "applicationOutcome");
        this.routePattern = Objects.requireNonNull(routePattern, "routePattern");
        retainedBytes = estimateRetainedBytes(
                headers, payload == null ? 0 : payload.length, keepAlive, stream != null || webSocket != null);
    }

    /** Serializes one committed application response before it returns to the event loop. */
    static PreparedResponse render(Response response, HttpVersion version, String requestMethod, boolean keepAlive) {
        return render(response, version, requestMethod, keepAlive, null, Outcome.success(), "<unmatched>");
    }

    /** Serializes one committed response and retains the request only for a WebSocket upgrade. */
    static PreparedResponse render(
            Response response,
            HttpVersion version,
            String requestMethod,
            boolean keepAlive,
            Request request) {
        return render(response, version, requestMethod, keepAlive, request, Outcome.success(), "<unmatched>");
    }

    /** Serializes an application dispatch with its outcome and safe route pattern. */
    static PreparedResponse render(
            Response response,
            HttpVersion version,
            String requestMethod,
            boolean keepAlive,
            Request request,
            Outcome applicationOutcome,
            String routePattern) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(applicationOutcome, "applicationOutcome");
        Objects.requireNonNull(routePattern, "routePattern");
        try {
            if (response.state() != ResponseState.COMMITTED) {
                throw new IllegalStateException("application returned a response that is not committed");
            }
            var body = ResponseDataReader.read(response);
            if (body != null && body.kind() == ResponseData.Kind.STREAM) {
                return stream(response, body, version, requestMethod, keepAlive, applicationOutcome, routePattern);
            }
            if (body != null && body.kind() == ResponseData.Kind.WEBSOCKET) {
                return webSocket(response, body, version, requestMethod, request, applicationOutcome, routePattern);
            }
            return new PreparedResponse(
                    version,
                    "HEAD".equals(requestMethod),
                    keepAlive,
                    response.status(),
                    response.headers(),
                    serialize(body),
                    null,
                    null,
                    null,
                    response,
                    applicationOutcome,
                    routePattern);
        } catch (RuntimeException | JsonProcessingException failure) {
            abort(response);
            return error(
                    500,
                    "Internal Server Error",
                    version,
                    requestMethod,
                    keepAlive,
                    Outcome.failure(Outcome.Kind.APPLICATION_FAILURE, failure),
                    routePattern);
        }
    }

    /** Creates a pre-serialized problem response without invoking an application renderer. */
    static PreparedResponse error(int status, String title, HttpVersion version, String requestMethod, boolean keepAlive) {
        return error(status, title, version, requestMethod, keepAlive, Outcome.success(), "<transport>");
    }

    /** Creates an error response with the known originating application outcome. */
    static PreparedResponse error(
            int status,
            String title,
            HttpVersion version,
            String requestMethod,
            boolean keepAlive,
            Outcome applicationOutcome,
            String routePattern) {
        var payload = ("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status + '}')
                .getBytes(StandardCharsets.UTF_8);
        var headers = Headers.builder()
                .set("Content-Type", "application/problem+json")
                .build();
        return new PreparedResponse(
                version,
                "HEAD".equals(requestMethod),
                keepAlive,
                status,
                headers,
                payload,
                null,
                null,
                null,
                null,
                applicationOutcome,
                routePattern);
    }

    /** Returns the bounded amount of response data retained until its ordered write completes. */
    long retainedBytes() {
        return retainedBytes;
    }

    boolean keepAlive() {
        return keepAlive;
    }

    /** Returns the final HTTP response status selected for the wire representation. */
    int status() {
        return status;
    }

    /** Returns the application result before the transport attempts its ordered write. */
    Outcome applicationOutcome() {
        return applicationOutcome;
    }

    /** Returns the route pattern or low-cardinality routing label for this response. */
    String routePattern() {
        return routePattern;
    }

    /** Returns aggregate body bytes, or {@code -1} for a stream or upgraded connection. */
    long responseBodyBytes() {
        if (isStreaming() || isWebSocket()) {
            return -1;
        }
        return headRequest ? 0 : payload.length;
    }

    /** Returns the application response headers retained for a specialized transport response. */
    Headers headers() {
        return headers;
    }

    /**
     * Returns this prepared response with HTTP keep-alive disabled.
     *
     * <p>This is used when an application commits a response before consuming its selected
     * streaming request body. The connection cannot safely accept a subsequent request while
     * that body still needs Flow demand, so the response becomes the final message on the
     * connection instead of retaining an idle, half-read peer.</p>
     */
    PreparedResponse closeAfterWrite() {
        if (!keepAlive) {
            return this;
        }
        return new PreparedResponse(
                version,
                headRequest,
                false,
                status,
                headers,
                payload,
                stream,
                webSocket,
                webSocketRequest,
                applicationResponse,
                applicationOutcome,
                routePattern);
    }

    /** Returns whether this response must be emitted as a Flow-controlled HTTP entity stream. */
    boolean isStreaming() {
        return stream != null;
    }

    /** Returns whether this response must transition the connection from HTTP/1.1 to WebSocket. */
    boolean isWebSocket() {
        return webSocket != null;
    }

    /** Returns the endpoint selected by the application after normal HTTP dispatch. */
    WebSocket webSocket() {
        if (webSocket == null) {
            throw new IllegalStateException("This prepared response is not a WebSocket upgrade");
        }
        return webSocket;
    }

    /** Returns the route-bound request retained for WebSocket session construction. */
    Request webSocketRequest() {
        if (webSocketRequest == null) {
            throw new IllegalStateException("This prepared response is not a WebSocket upgrade");
        }
        return webSocketRequest;
    }

    /** Returns whether this response was created for an HTTP HEAD request. */
    boolean isHeadRequest() {
        return headRequest;
    }

    /** Returns the stream publisher after the transport has written response headers. */
    Flow.Publisher<ByteBuffer> stream() {
        if (stream == null) {
            throw new IllegalStateException("This prepared response has an aggregate payload");
        }
        return stream;
    }

    /** Builds a Netty response using only data that was prepared outside the event loop. */
    FullHttpResponse toNettyResponse() {
        if (isStreaming() || isWebSocket()) {
            throw new IllegalStateException("Streaming and WebSocket responses require specialized transport writes");
        }
        var content = headRequest ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(payload);
        var outbound = new DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(status), content);
        copyHeaders(headers, outbound);
        HttpUtil.setContentLength(outbound, payload.length);
        if (keepAlive) {
            outbound.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        } else {
            outbound.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        return outbound;
    }

    /**
     * Builds an HTTP-object response for an HTTP/2 stream codec.
     *
     * <p>{@code Http2StreamFrameToHttpObjectCodec} accepts HTTP/1.1-shaped objects but converts
     * them to HTTP/2 frames. Hop-by-hop headers and chunk framing are deliberately absent: DATA
     * END_STREAM, not {@code Connection} or {@code Transfer-Encoding}, owns HTTP/2 completion.</p>
     */
    FullHttpResponse toNettyHttp2Response() {
        if (isStreaming() || isWebSocket()) {
            throw new IllegalStateException("Streaming and WebSocket responses require specialized HTTP/2 writes");
        }
        var content = headRequest ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(payload);
        var outbound = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), content);
        copyHeaders(headers, outbound);
        HttpUtil.setContentLength(outbound, payload.length);
        return outbound;
    }

    /** Builds only the HTTP response head for a chunked HTTP/1.1 stream. */
    HttpResponse toNettyStreamHeaders() {
        if (!isStreaming()) {
            throw new IllegalStateException("Aggregate responses require a full response write");
        }
        var outbound = new DefaultHttpResponse(version, HttpResponseStatus.valueOf(status));
        copyHeaders(headers, outbound);
        if (HttpVersion.HTTP_1_1.equals(version)) {
            HttpUtil.setTransferEncodingChunked(outbound, true);
        }
        if (keepAlive) {
            outbound.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        } else {
            outbound.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }
        return outbound;
    }

    /** Builds only the response head for an HTTP/2 Flow entity stream. */
    HttpResponse toNettyHttp2StreamHeaders() {
        if (!isStreaming()) {
            throw new IllegalStateException("Aggregate responses require a full response write");
        }
        var outbound = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
        copyHeaders(headers, outbound);
        return outbound;
    }

    /** Marks the application response complete after a successful transport write. */
    void complete() {
        if (applicationResponse != null) {
            applicationResponse.complete();
        }
    }

    /** Aborts the application response when it cannot be written. This operation is idempotent. */
    void abort() {
        if (applicationResponse != null) {
            abort(applicationResponse);
        }
    }

    private static void copyHeaders(Headers headers, io.netty.handler.codec.http.HttpMessage outbound) {
        headers.asMap().forEach((name, values) -> {
            if (!name.equalsIgnoreCase(HttpHeaderNames.CONTENT_LENGTH.toString())
                    && !name.equalsIgnoreCase(HttpHeaderNames.CONNECTION.toString())
                    && !name.equalsIgnoreCase(HttpHeaderNames.TRANSFER_ENCODING.toString())) {
                values.forEach(value -> outbound.headers().add(name, value));
            }
        });
    }

    private static PreparedResponse stream(
            Response response,
            ResponseData body,
            HttpVersion version,
            String requestMethod,
            boolean keepAlive,
            Outcome applicationOutcome,
            String routePattern) {
        var publisher = body.stream().orElseThrow(() -> new IllegalStateException("stream body omitted publisher"));
        // HTTP/1.0 has no chunk framing. Its entity is delimited by the mandatory connection close.
        var streamKeepAlive = HttpVersion.HTTP_1_0.equals(version) ? false : keepAlive;
        return new PreparedResponse(
                version,
                "HEAD".equals(requestMethod),
                streamKeepAlive,
                response.status(),
                response.headers(),
                null,
                publisher,
                null,
                null,
                response,
                applicationOutcome,
                routePattern);
    }

    private static PreparedResponse webSocket(
            Response response,
            ResponseData body,
            HttpVersion version,
            String requestMethod,
            Request request,
            Outcome applicationOutcome,
            String routePattern) {
        var endpoint = body.webSocket().orElseThrow(() -> new IllegalStateException("WebSocket body omitted endpoint"));
        if (request == null) {
            throw new IllegalStateException("WebSocket response requires its initiating request");
        }
        return new PreparedResponse(
                version,
                "HEAD".equals(requestMethod),
                true,
                101,
                response.headers(),
                null,
                null,
                endpoint,
                request,
                response,
                applicationOutcome,
                routePattern);
    }

    private static byte[] serialize(ResponseData body) throws JsonProcessingException {
        if (body == null) {
            return new byte[0];
        }
        return switch (body.kind()) {
            case BYTES -> body.byteContent().orElseThrow();
            case JSON -> JSON.writeValueAsBytes(body.value());
            case PROBLEM -> JSON.writeValueAsBytes(problemDocument((Problem) body.value()));
            case STREAM -> throw new IllegalStateException("stream response must not be materialized");
            case WEBSOCKET -> throw new IllegalStateException("WebSocket response must not be materialized");
        };
    }

    private static Map<String, Object> problemDocument(Problem problem) {
        var document = new LinkedHashMap<String, Object>();
        document.put("type", problem.type().toString());
        document.put("title", problem.title());
        document.put("status", problem.status());
        problem.detail().ifPresent(detail -> document.put("detail", detail));
        problem.instance().ifPresent(instance -> document.put("instance", instance.toString()));
        document.putAll(problem.extensions());
        return document;
    }

    private static long estimateRetainedBytes(Headers headers, int payloadBytes, boolean keepAlive, boolean streaming) {
        var total = 32L; // status line and terminating CRLF
        for (var entry : headers.asMap().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(HttpHeaderNames.CONTENT_LENGTH.toString())
                    || entry.getKey().equalsIgnoreCase(HttpHeaderNames.CONNECTION.toString())
                    || entry.getKey().equalsIgnoreCase(HttpHeaderNames.TRANSFER_ENCODING.toString())) {
                continue;
            }
            for (var value : entry.getValue()) {
                total = saturatingAdd(total, worstCaseUtf8Bytes(entry.getKey()));
                total = saturatingAdd(total, 2);
                total = saturatingAdd(total, worstCaseUtf8Bytes(value));
                total = saturatingAdd(total, 2);
            }
        }
        if (streaming) {
            total = saturatingAdd(total, "Transfer-Encoding".length() + 2L + "chunked".length() + 2L);
        } else {
            total = saturatingAdd(total, "Content-Length".length() + 2L + Long.toString(payloadBytes).length() + 2L);
        }
        total = saturatingAdd(total, "Connection".length() + 2L
                + (keepAlive ? "keep-alive".length() : "close".length()) + 2L);
        return saturatingAdd(total, payloadBytes);
    }

    private static long worstCaseUtf8Bytes(String value) {
        return value.length() > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : value.length() * 4L;
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static void abort(Response response) {
        if (response.state() != ResponseState.COMPLETED && response.state() != ResponseState.ABORTED) {
            response.abort();
        }
    }
}
