package io.wavejava.wave.api.http;

import io.wavejava.wave.api.render.Rendered;
import io.wavejava.wave.api.websocket.WebSocket;
import io.wavejava.wave.internal.http.InternalResponse;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;

/**
 * Mutable response controls for one request invocation.
 *
 * <p>The response payload is intentionally not part of the public HTTP model. Applications choose
 * a writer, while the transport reads the committed representation through an internal boundary.</p>
 */
public abstract sealed class Response permits InternalResponse {
    protected Response() {
    }

    /** Creates a new open response with status {@code 200}. */
    public static Response create() {
        return new InternalResponse();
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

    public abstract Response webSocket(WebSocket endpoint);

    public abstract Response json(Object value);

    public abstract Response render(Rendered rendered);

    public abstract Response problem(Problem problem);

    public abstract Response empty();

    public abstract Response commit();

    public abstract Response redirect(int status, String location);

    public abstract Response complete();

    public abstract Response abort();
}
