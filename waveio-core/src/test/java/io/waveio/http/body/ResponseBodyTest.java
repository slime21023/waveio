package io.waveio.http.body;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResponseBodyTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyBytesBody() {
        var empty = ResponseBody.empty();
        assertEquals(0, empty.value().length);
        assertEquals(OptionalLong.of(0), empty.contentLength());
    }

    @Test
    void bytesBodyProtectsImmutability() {
        byte[] original = new byte[]{1, 2, 3};
        var body = ResponseBody.bytes(original);

        assertEquals(OptionalLong.of(3), body.contentLength());
        assertArrayEquals(new byte[]{1, 2, 3}, body.value());

        original[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3}, body.value());

        byte[] returned = body.value();
        returned[0] = 88;
        assertArrayEquals(new byte[]{1, 2, 3}, body.value());
    }

    @Test
    void streamBodyWithoutContentLength() {
        Flow.Publisher<ByteBuffer> pub = subscriber -> {};
        var stream = ResponseBody.stream(pub);

        assertEquals(pub, stream.publisher());
        assertFalse(stream.contentLength().isPresent());
    }

    @Test
    void streamBodyWithContentLength() {
        Flow.Publisher<ByteBuffer> pub = subscriber -> {};
        var stream = ResponseBody.stream(pub, 1024L);

        assertEquals(pub, stream.publisher());
        assertEquals(1024L, stream.contentLength().orElseThrow());

        assertThrows(IllegalArgumentException.class, () -> ResponseBody.stream(pub, -1));
    }

    @Test
    void streamRecordValidation() {
        Flow.Publisher<ByteBuffer> pub = subscriber -> {};
        assertThrows(NullPointerException.class, () -> new ResponseBody.Stream(null, OptionalLong.empty()));
        assertThrows(NullPointerException.class, () -> new ResponseBody.Stream(pub, null));
        assertThrows(IllegalArgumentException.class, () -> new ResponseBody.Stream(pub, OptionalLong.of(-5)));
    }

    @Test
    void fileBody() throws Exception {
        assertThrows(NullPointerException.class, () -> ResponseBody.file(null));

        Path nonExistent = tempDir.resolve("missing.txt");
        assertThrows(UncheckedIOException.class, () -> ResponseBody.file(nonExistent));

        Path existing = tempDir.resolve("existing.txt");
        Files.writeString(existing, "sample payload");
        var body = ResponseBody.file(existing);

        assertTrue(body.contentLength().isPresent());
        assertEquals(Files.size(existing), body.contentLength().getAsLong());
    }
}
