package io.waveio.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ResponseTransactionTest {
    @Test void responseCommitsExactlyOnce() {
        ResponseTransaction transaction = new ResponseTransaction(); transaction.commit(HttpResponse.of(HttpStatus.OK));
        assertTrue(transaction.isCommitted()); assertEquals(HttpStatus.OK, transaction.committed().orElseThrow().status());
        assertThrows(IllegalStateException.class, () -> transaction.commit(HttpResponse.of(HttpStatus.NOT_FOUND)));
    }
}
