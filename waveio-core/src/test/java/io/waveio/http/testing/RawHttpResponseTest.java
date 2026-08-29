package io.waveio.http.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RawHttpResponseTest {
    @Test
    void readsContentLengthResponse() throws Exception {
        var response = read("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nX-Test: yes\r\n\r\nbody");

        assertEquals(200, response.status());
        assertEquals("yes", response.header("X-Test").orElseThrow());
        assertEquals("body", response.bodyText());
    }

    @Test
    void readsChunkedResponseWithExtensionsAndTrailers() throws Exception {
        var response = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3;name=value\r\none\r\n3\r\ntwo\r\n0\r\nX-Trailer: done\r\n\r\n");

        assertEquals("onetwo", response.bodyText());
    }

    private static RawHttpResponse read(String wire) throws Exception {
        return RawHttpResponse.read(new ByteArrayInputStream(
                wire.getBytes(StandardCharsets.US_ASCII)));
    }
}
