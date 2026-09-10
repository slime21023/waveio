package io.wavejava.wave.api.http;

/** Thrown when code attempts a second read from a request {@link Body}. */
public final class BodyAlreadyConsumedException extends IllegalStateException {
    public BodyAlreadyConsumedException() {
        super("Request body has already been consumed");
    }
}
