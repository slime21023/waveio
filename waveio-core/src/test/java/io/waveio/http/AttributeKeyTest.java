package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AttributeKeyTest {

    @Test
    void createsKeyWithValidName() {
        AttributeKey<String> key = AttributeKey.of("test.key");
        assertEquals("test.key", key.name());
    }

    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class, () -> AttributeKey.of(null));
    }

    @Test
    void rejectsEmptyOrBlankName() {
        assertThrows(IllegalArgumentException.class, () -> AttributeKey.of(""));
        assertThrows(IllegalArgumentException.class, () -> AttributeKey.of("   "));
        assertThrows(IllegalArgumentException.class, () -> AttributeKey.of("\t\n"));
    }
}
