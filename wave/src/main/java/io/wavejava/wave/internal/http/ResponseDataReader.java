package io.wavejava.wave.internal.http;

import io.wavejava.wave.api.http.Response;

/** Internal transport-only access to a committed response representation. */
public final class ResponseDataReader {
    private ResponseDataReader() {
    }

    /** Returns the committed representation, or {@code null} for an empty response. */
    public static ResponseData read(Response response) {
        if (!(response instanceof InternalResponse internal)) {
            throw new IllegalArgumentException("Unsupported response implementation: " + response.getClass());
        }
        return internal.data();
    }
}
