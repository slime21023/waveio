package io.waveio.http.body;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RequestBodyTest {

    @Test
    void emptyBodyBehavesCorrectly() {
        RequestBody body = RequestBody.empty();
        assertEquals(0, body.size());
        assertTrue(body.isEmpty());
        assertEquals(0, body.bytes().length);
        assertEquals("", body.text());
        assertEquals("", body.text(StandardCharsets.ISO_8859_1));
    }

    @Test
    void ofBytesProtectsImmutability() {
        byte[] original = "hello wave".getBytes(StandardCharsets.UTF_8);
        RequestBody body = RequestBody.of(original);

        assertEquals(original.length, body.size());
        assertFalse(body.isEmpty());
        assertEquals("hello wave", body.text());
        assertEquals("hello wave", body.text(StandardCharsets.UTF_8));

        // Mutating original array does not affect RequestBody
        original[0] = 'X';
        assertEquals("hello wave", body.text());

        // Mutating returned array does not affect RequestBody
        byte[] returned = body.bytes();
        returned[0] = 'Y';
        assertEquals("hello wave", body.text());
        assertArrayEquals("hello wave".getBytes(StandardCharsets.UTF_8), body.bytes());
    }
}
