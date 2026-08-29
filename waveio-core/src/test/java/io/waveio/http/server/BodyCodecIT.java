package io.waveio.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpResponse;
import io.waveio.http.body.BodyCodec;
import io.waveio.http.testing.RawHttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** A configured codec must carry values over a real connection, and its absence must fail safely. */
class BodyCodecIT {

    private record Item(String name, int quantity) {}

    @Test
    void encodesAValueBodyWithTheConfiguredCodecAndItsContentType() throws Exception {
        try (var server = HttpServer.builder()
                .port(0)
                .bodyCodec(new RecordCodec())
                .get("/item", request -> HttpResponse.value(new Item("widget", 3)))
                .build()
                .start();
                var client = RawHttpClient.connect(server.localPort())) {
            client.send("GET /item HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            var response = client.readResponse();
            assertEquals(200, response.status());
            assertEquals("widget:3", response.bodyText());
            assertEquals("application/x-record", response.header("content-type").orElseThrow());
        }
    }

    @Test
    void aValueBodyWithoutACodecAnswersWithServerErrorRatherThanCorruptOutput() throws Exception {
        try (var server = HttpServer.builder()
                .port(0)
                .get("/item", request -> HttpResponse.value(new Item("widget", 3)))
                .build()
                .start();
                var client = RawHttpClient.connect(server.localPort())) {
            client.send("GET /item HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

            var response = client.readResponse();
            assertEquals(500, response.status());
            assertTrue(response.bodyText().contains("Internal Server Error"));
        }
    }

    @Test
    void decodesARequestBodyAndRejectsAMalformedOneWithBadRequest() throws Exception {
        try (var server = HttpServer.builder()
                .port(0)
                .bodyCodec(new RecordCodec())
                .post("/item", request -> HttpResponse.text(request.bodyAs(Item.class).name()))
                .build()
                .start();
                var client = RawHttpClient.connect(server.localPort())) {
            client.send("POST /item HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 8\r\n\r\nWIDGET:3");
            var accepted = client.readResponse();
            assertEquals(200, accepted.status());
            assertEquals("widget", accepted.bodyText());

            client.send("POST /item HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 7\r\nConnection: close\r\n\r\ngarbage");
            var rejected = client.readResponse();
            assertEquals(400, rejected.status());
        }
    }

    /** Deliberately trivial: WaveIO ships no codec, so the test supplies its own. */
    private static final class RecordCodec implements BodyCodec {
        @Override public String contentType() { return "application/x-record"; }

        @Override public byte[] encode(Object value) {
            var item = (Item) value;
            return (item.name() + ":" + item.quantity()).getBytes(StandardCharsets.UTF_8);
        }

        @Override public <T> T decode(byte[] body, Class<T> type) {
            var parts = new String(body, StandardCharsets.UTF_8).split(":", 2);
            return type.cast(new Item(parts[0].toLowerCase(Locale.ROOT),
                    Integer.parseInt(parts[1])));
        }
    }
}
