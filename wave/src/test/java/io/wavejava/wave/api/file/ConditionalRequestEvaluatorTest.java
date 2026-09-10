package io.wavejava.wave.api.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.http.Request;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConditionalRequestEvaluatorTest {
    private static final Instant LAST_MODIFIED = Instant.parse("2026-09-09T00:00:00.999Z");
    private static final String ETAG = "\"a-1\"";

    @Test
    void ifNoneMatchTakesPrecedenceAndUsesWeakComparisonForGet() {
        var request = Request.builder()
                .path("/asset")
                .header("If-None-Match", "\"other\", W/\"a-1\"")
                .header("If-Modified-Since", ConditionalRequestEvaluator.formatHttpDate(Instant.EPOCH))
                .build();

        assertEquals(
                ConditionalRequestEvaluator.Result.NOT_MODIFIED,
                ConditionalRequestEvaluator.evaluate(request, ETAG, LAST_MODIFIED));
    }

    @Test
    void modifiedSinceUsesSecondPrecisionAndInvalidDatesAreIgnored() {
        var notModified = Request.builder()
                .path("/asset")
                .header("If-Modified-Since", ConditionalRequestEvaluator.formatHttpDate(LAST_MODIFIED))
                .build();
        var invalid = Request.builder().path("/asset").header("If-Modified-Since", "invalid").build();

        assertEquals(
                ConditionalRequestEvaluator.Result.NOT_MODIFIED,
                ConditionalRequestEvaluator.evaluate(notModified, ETAG, LAST_MODIFIED));
        assertEquals(
                ConditionalRequestEvaluator.Result.PROCEED,
                ConditionalRequestEvaluator.evaluate(invalid, ETAG, LAST_MODIFIED));
        assertTrue(ConditionalRequestEvaluator.parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT").isPresent());
        assertFalse(ConditionalRequestEvaluator.parseHttpDate("not-a-date").isPresent());
    }
}
