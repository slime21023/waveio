package io.wavejava.wave.api.sse;

import java.io.IOException;

/** A malformed or unsupported SSE HTTP response or event-stream wire sequence. */
public final class SseProtocolException extends IOException {
    /** Creates a protocol failure with a diagnostic message. */
    public SseProtocolException(String message) {
        super(message);
    }

    /** Creates a protocol failure with its underlying cause. */
    public SseProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
