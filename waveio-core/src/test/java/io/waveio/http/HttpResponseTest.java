package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.body.ResponseBody;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpResponseTest {

    @TempDir
    Path tempDir;

    @Test
    void createsTextResponse() {
        var response = HttpResponse.text("hello world");
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("text/plain; charset=utf-8", response.headers().first("content-type").orElseThrow());
        assertTrue(response.body() instanceof ResponseBody.Bytes);
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), ((ResponseBody.Bytes) response.body()).value());
    }

    @Test
    void createsJsonResponse() {
        var response = HttpResponse.json("{\"ok\":true}");
        assertEquals(HttpStatus.OK, response.status());
        assertEquals("application/json; charset=utf-8", response.headers().first("content-type").orElseThrow());
        assertTrue(response.body() instanceof ResponseBody.Bytes);
        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), ((ResponseBody.Bytes) response.body()).value());
    }

    @Test
    void createsNoContentResponse() {
        var response = HttpResponse.noContent();
        assertEquals(HttpStatus.NO_CONTENT, response.status());
        assertTrue(response.body() instanceof ResponseBody.Bytes);
        assertEquals(0, ((ResponseBody.Bytes) response.body()).value().length);
    }

    @Test
    void buildsResponseWithStatusAndHeadersAndCookies() {
        var cookie = Cookie.builder("session", "abc").path("/").build();
        var response = HttpResponse.status(HttpStatus.CREATED)
                .header("X-Custom", "123")
                .cookie(cookie)
                .body("created body");

        assertEquals(HttpStatus.CREATED, response.status());
        assertEquals("123", response.headers().first("x-custom").orElseThrow());
        assertEquals("session=abc; Path=/", response.headers().first("set-cookie").orElseThrow());
        assertArrayEquals("created body".getBytes(StandardCharsets.UTF_8), ((ResponseBody.Bytes) response.body()).value());
    }

    @Test
    void buildsResponseWithTextAndJsonMethods() {
        var accepted = HttpStatus.of(202, "Accepted");
        var textResp = HttpResponse.status(accepted).text("accepted text");
        assertEquals(accepted, textResp.status());
        assertEquals("text/plain; charset=utf-8", textResp.headers().first("content-type").orElseThrow());

        var jsonResp = HttpResponse.status(HttpStatus.BAD_REQUEST).json("{\"error\":\"bad\"}");
        assertEquals(HttpStatus.BAD_REQUEST, jsonResp.status());
        assertEquals("application/json; charset=utf-8", jsonResp.headers().first("content-type").orElseThrow());
    }

    @Test
    void buildsResponseWithRawBytesAndCustomResponseBody() {
        byte[] bytes = new byte[]{1, 2, 3};
        var resp1 = HttpResponse.status(HttpStatus.OK).body(bytes);
        assertArrayEquals(bytes, ((ResponseBody.Bytes) resp1.body()).value());

        var customBody = ResponseBody.bytes(new byte[]{4, 5});
        var resp2 = HttpResponse.status(HttpStatus.OK).body(customBody);
        assertEquals(customBody, resp2.body());

        var emptyResp = HttpResponse.status(HttpStatus.OK).build();
        assertEquals(0, ((ResponseBody.Bytes) emptyResp.body()).value().length);
    }

    @Test
    void buildsResponseWithStreamAndFile() throws Exception {
        Flow.Publisher<ByteBuffer> dummyPublisher = subscriber -> {};
        var streamResp = HttpResponse.status(HttpStatus.OK).stream(dummyPublisher);
        assertTrue(streamResp.body() instanceof ResponseBody.Stream);

        var streamWithLenResp = HttpResponse.status(HttpStatus.OK).stream(dummyPublisher, 100L);
        assertEquals(100L, ((ResponseBody.Stream) streamWithLenResp.body()).contentLength().orElseThrow());

        Path tempFile = tempDir.resolve("test.txt");
        Files.writeString(tempFile, "sample text");
        var fileResp = HttpResponse.status(HttpStatus.OK).file(tempFile);
        assertTrue(fileResp.body() instanceof ResponseBody.Stream);
        assertEquals("sample text".length(), ((ResponseBody.Stream) fileResp.body()).contentLength().orElseThrow());
    }
}
