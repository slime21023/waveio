package io.wavejava.wave.api.client;

import java.util.concurrent.CancellationException;

/** Indicates that a caller cancellation token or client lifecycle cancelled an exchange. */
public final class ClientCancelledException extends CancellationException {
    public ClientCancelledException(String reason) {
        super(reason);
    }
}
