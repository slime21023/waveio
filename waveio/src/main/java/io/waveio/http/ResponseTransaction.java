package io.waveio.http;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** A single-commit response transaction. */
public final class ResponseTransaction {
    private final AtomicReference<HttpResponse> response = new AtomicReference<>();
    /** Creates an uncommitted response transaction. */ public ResponseTransaction() { }
    /** Commits a response exactly once. */ public void commit(HttpResponse candidate) {
        if (!response.compareAndSet(null, Objects.requireNonNull(candidate, "candidate"))) { throw new IllegalStateException("HTTP response has already been committed"); }
    }
    /** Returns the committed response when present. */ public Optional<HttpResponse> committed() { return Optional.ofNullable(response.get()); }
    /** Returns whether a response is committed. */ public boolean isCommitted() { return response.get() != null; }
}
