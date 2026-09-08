package io.waveio.server;

import io.waveio.http.Body;
import io.waveio.http.Headers;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Flow;

/** Small built-in response helpers for text and bytes. */
public final class Responses {
    private Responses() { }
    /** Creates a UTF-8 plain-text 200 response. */
    public static HttpResponse text(String value) { return bytes(value.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8"); }
    /** Creates an octet-stream 200 response. */
    public static HttpResponse bytes(byte[] value) { return bytes(value, "application/octet-stream"); }
    /** Creates a 200 response with the supplied content type. */
    public static HttpResponse bytes(byte[] value, String contentType) {
        Objects.requireNonNull(value, "value"); Objects.requireNonNull(contentType, "contentType");
        byte[] copy = value.clone();
        Headers headers = Headers.builder().add("content-type", contentType).add("content-length", Integer.toString(copy.length)).build();
        return new HttpResponse(HttpStatus.OK, headers, Body.of(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean delivered;
            @Override public void request(long demand) { if (demand <= 0) { subscriber.onError(new IllegalArgumentException("non-positive demand")); } else if (!delivered) { delivered = true; subscriber.onNext(ByteBuffer.wrap(copy).asReadOnlyBuffer()); subscriber.onComplete(); } }
            @Override public void cancel() { delivered = true; }
        })));
    }
}
