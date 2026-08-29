package io.waveio.http.internal.netty;

import java.util.Objects;

sealed interface WriteResult permits WriteResult.Success, WriteResult.Failure {
    record Success() implements WriteResult {}

    record Failure(Throwable cause) implements WriteResult {
        public Failure {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
