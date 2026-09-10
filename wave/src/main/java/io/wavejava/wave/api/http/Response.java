package io.wavejava.wave.api.http;

import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * Mutable response controls for one request invocation.
 *
 * <p>The response payload is intentionally not part of the public HTTP model. Applications choose
 * a writer, while the transport reads the committed representation through an internal boundary.
 * Wave supplies the sole supported implementation; applications must use this contract rather than
 * subclassing it.</p>
 */
public abstract class Response {
    /** For Wave's implementation only; custom response implementations are unsupported. */
    protected Response() {
    }

    public abstract ResponseState state();

    public abstract boolean isCommitted();

    public abstract int status();

    public abstract Response status(int status);

    public abstract Response header(String name, String value);

    public abstract Response addHeader(String name, String value);

    public abstract Response removeHeader(String name);

    public abstract Response cookie(Cookie cookie);

    public abstract Headers headers();

    public abstract Response text(String text);

    public abstract Response bytes(byte[] bytes, MediaType mediaType);

    public abstract Response stream(Flow.Publisher<ByteBuffer> publisher);

    /** Selects a protocol upgrade represented by a public HTTP upgrade contract. */
    public abstract Response upgrade(ResponseUpgrade upgrade);

    public abstract Response json(Object value);

    public abstract Response problem(Problem problem);

    public abstract Response empty();

    public abstract Response commit();

    public abstract Response redirect(int status, String location);

    public abstract Response complete();

    public abstract Response abort();
}
