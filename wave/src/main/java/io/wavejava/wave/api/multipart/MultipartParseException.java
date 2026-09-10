package io.wavejava.wave.api.multipart;

/** Raised when a multipart body or its declared boundary is structurally invalid. */
public final class MultipartParseException extends IllegalArgumentException {
    MultipartParseException(String message) {
        super(message);
    }

    MultipartParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
