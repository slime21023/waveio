package io.wavejava.wave.api.multipart;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Raised when an in-progress multipart parse is cancelled before it produces a form. */
public final class MultipartCancelledException extends CancellationException {
    private final String reason;

    MultipartCancelledException(String reason) {
        super("Multipart parsing cancelled: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    /** Returns the cancellation diagnostic supplied by the caller or request token. */
    public String reason() {
        return reason;
    }
}
