package io.wavejava.wave.api.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.form.FormData;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BodyTest {
    @Test
    void bodyIsBoundedCopiedAndConsumedExactlyOnce() {
        var inbound = "hello".getBytes(StandardCharsets.UTF_8);
        var body = Body.of(inbound, 5);
        inbound[0] = 'x';

        assertEquals(5, body.length());
        assertEquals(5, body.maximumBytes());
        assertFalse(body.isConsumed());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), body.bytes());
        assertTrue(body.isConsumed());
        assertThrows(BodyAlreadyConsumedException.class, body::text);
    }

    @Test
    void oversizedBodyFailsBeforeItCanBeObserved() {
        var exception = assertThrows(BodyTooLargeException.class, () -> Body.of(new byte[3], 2));

        assertEquals(3, exception.actualBytes());
        assertEquals(2, exception.maximumBytes());
    }

    @Test
    void decodesJsonExactlyOnce() {
        var body = Body.utf8("{\"name\":\"wave\"}", 32);

        var value = body.json(java.util.Map.class);

        assertEquals("wave", value.get("name"));
        assertThrows(BodyAlreadyConsumedException.class, body::bytes);
    }

    @Test
    void decodesUrlEncodedFormsExactlyOnce() {
        var body = Body.utf8("name=Ada+Lovelace&tag=java&tag=web", 64);

        var form = new io.wavejava.wave.api.form.UrlEncodedFormParser().parse(body);

        assertEquals("Ada Lovelace", form.first("name").orElseThrow());
        assertEquals(java.util.List.of("java", "web"), form.values("tag"));
        assertTrue(body.isConsumed());
        assertThrows(BodyAlreadyConsumedException.class, body::bytes);
    }
}
