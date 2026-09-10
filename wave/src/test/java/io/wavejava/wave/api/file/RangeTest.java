package io.wavejava.wave.api.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RangeTest {
    @Test
    void resolvesClosedOpenAndSuffixRanges() {
        var closed = Range.parse("bytes=2-4").resolve(10).orElseThrow();
        assertEquals(2, closed.start());
        assertEquals(4, closed.endInclusive());
        assertEquals(3, closed.length());

        var open = Range.parse("bytes=7-").resolve(10).orElseThrow();
        assertEquals(7, open.start());
        assertEquals(9, open.endInclusive());

        var suffix = Range.parse("bytes=-4").resolve(10).orElseThrow();
        assertEquals(6, suffix.start());
        assertEquals(9, suffix.endInclusive());
    }

    @Test
    void rejectsMalformedAndReportsUnsatisfiableRanges() {
        assertThrows(IllegalArgumentException.class, () -> Range.parse("items=0-1"));
        assertThrows(IllegalArgumentException.class, () -> Range.parse("bytes=0-1,3-4"));
        assertThrows(IllegalArgumentException.class, () -> Range.parse("bytes=5-3"));
        assertFalse(Range.parse("bytes=10-").resolve(10).isPresent());
        assertFalse(Range.parse("bytes=-0").resolve(10).isPresent());
    }
}
