package io.waveio.http;

import io.waveio.http.testing.DefaultHttpServerITSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.waveio.http.testing.RawHttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestTargetIT extends DefaultHttpServerITSupport {
    @Test
    void decodesCanonicalPathAndQueryOverRawTcp() throws Exception {
        try (var connection = RawHttpClient.connect(server.localPort())) {
            var response = connection.send(
                    "GET /caf%C3%A9/a%20b?tag=x+y&tag=z%2By HTTP/1.1\r\n"
                            + "Host: localhost\r\nConnection: close\r\n\r\n")
                    .readResponse();

            assertEquals(200, response.status());
            assertEquals("a b:x y:z+y", response.bodyText());
        }
    }

    @Test
    void acceptsAbsoluteFormRequestTargetOverRawTcp() throws Exception {
        try (var connection = RawHttpClient.connect(server.localPort())) {
            var response = connection.send(
                    "GET http://localhost/caf%C3%A9/item?tag=value HTTP/1.1\r\n"
                            + "Host: localhost\r\nConnection: close\r\n\r\n")
                    .readResponse();

            assertEquals(200, response.status());
            assertEquals("item:value", response.bodyText());
        }
    }

    @Test
    void rejectsMalformedOrUnsafeRequestTargetsOverRawTcp() throws Exception {
        for (String target : List.of(
                "/files/a%2Fb",
                "/safe/%2e%2e/secret",
                "/bad/%C3%28",
                "relative/path")) {
            try (var connection = RawHttpClient.connect(server.localPort())) {
                var response = connection.send("GET " + target + " HTTP/1.1\r\n"
                                + "Host: localhost\r\nConnection: close\r\n\r\n")
                        .readResponse();

                assertEquals(400, response.status(), target);
            }
        }
    }
}
