package io.wavejava.wave.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class RequestContextTest {
    @Test
    void contextSnapshotsDataAndExposesDeadlineAndCancellation() {
        var sourceData = new LinkedHashMap<String, Object>();
        sourceData.put("traceId", "trace-42");
        var token = CancellationToken.create();
        var deadline = Deadline.at(Instant.parse("2030-01-01T00:00:10Z"));
        var context = RequestContext.builder("request-42")
                .data(sourceData)
                .data("principal", "alex")
                .deadline(deadline)
                .cancellationToken(token)
                .build();
        sourceData.put("traceId", "changed-after-build");

        assertEquals("request-42", context.requestId());
        assertEquals("trace-42", context.data("traceId").orElseThrow());
        assertEquals("alex", context.data("principal").orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> context.data().put("other", "value"));
        assertSame(deadline, context.deadline().orElseThrow());
        assertFalse(context.isDeadlineExceeded(Instant.parse("2030-01-01T00:00:09Z")));
        assertTrue(context.isDeadlineExceeded(Instant.parse("2030-01-01T00:00:10Z")));
        assertSame(token, context.cancellationToken());
        assertFalse(context.isCancelled());

        token.cancel("deadline elapsed");

        assertTrue(context.isCancelled());
        assertEquals("deadline elapsed", context.cancellationReason().orElseThrow());
    }

    @Test
    void basicContextHasNoDeadlineAndRequiresARequestId() {
        var context = RequestContext.of("request-7");

        assertTrue(context.deadline().isEmpty());
        assertFalse(context.isDeadlineExceeded(Instant.EPOCH));
        assertFalse(context.isCancelled());
        assertThrows(IllegalArgumentException.class, () -> RequestContext.of(" "));
        assertThrows(NullPointerException.class, () -> RequestContext.builder().data("key", "value").build());
    }
}
