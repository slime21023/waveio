package io.waveio.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpResponse;
import io.waveio.http.testing.RawHttpClient;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** Interim `100 Continue` responses must respect response order and server codec framing. */
class ExpectContinueIT {

    @Test
    void interimContinueDoesNotPrecedeAnEarlierPendingResponse() throws Exception {
        var pending = new CompletableFuture<HttpResponse>();
        try (var server = HttpServer.builder()
                .port(0)
                .handlerTimeout(Duration.ofSeconds(5))
                .asyncGet("/first", request -> pending)
                .streamingPost("/upload", (request, body) -> collect(body)
                        .thenApply(value -> HttpResponse.text("body:" + value)))
                .build()
                .start();
                var client = RawHttpClient.connect(server.localPort())) {
            client.send("GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "Expect: 100-continue\r\nContent-Length: 5\r\n\r\n");
            Thread.sleep(200);
            pending.complete(HttpResponse.text("first"));

            var first = client.readResponse();
            assertEquals(200, first.status());
            assertEquals("first", first.bodyText());

            var interim = client.readResponse();
            assertEquals(100, interim.status());

            client.send("hello");
            var upload = client.readResponse();
            assertEquals(200, upload.status());
            assertEquals("body:hello", upload.bodyText());
        }
    }

    @Test
    void interimContinueKeepsCodecResponseFramingForTheFollowingRequest() throws Exception {
        var release = new CompletableFuture<Void>();
        try (var server = HttpServer.builder()
                .port(0)
                .maxBodySize(64)
                .handlerTimeout(Duration.ofSeconds(5))
                .asyncPost("/echo", request -> release
                        .thenApply(ignored -> HttpResponse.text(request.body().text())))
                .get("/text", request -> HttpResponse.text("representation"))
                .build()
                .start();
                var client = RawHttpClient.connect(server.localPort())) {
            client.send("POST /echo HTTP/1.1\r\nHost: localhost\r\n"
                    + "Expect: 100-continue\r\nContent-Length: 5\r\n\r\n");
            assertEquals(100, client.readResponse().status());

            // The awaited body plus a pipelined HEAD, held until both are decoded, so the codec's
            // request-method queue holds HEAD while the earlier POST response is encoded.
            client.send("hello"
                    + "HEAD /text HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            Thread.sleep(200);
            release.complete(null);

            var echo = client.readResponse();
            assertEquals(200, echo.status());
            assertEquals("hello", echo.bodyText());

            String head = client.readHeaders();
            assertTrue(head.startsWith("HTTP/1.1 200 OK"), head);
            assertEquals(-1, client.socket().getInputStream().read(),
                    "HEAD response must not carry a body");
        }
    }

    private static CompletableFuture<String> collect(Flow.Publisher<ByteBuffer> publisher) {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }
            @Override public void onNext(ByteBuffer value) {
                var chunk = new byte[value.remaining()];
                value.get(chunk);
                bytes.writeBytes(chunk);
                subscription.request(1);
            }
            @Override public void onError(Throwable failure) {
                result.completeExceptionally(failure);
            }
            @Override public void onComplete() {
                result.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        });
        return result;
    }
}
