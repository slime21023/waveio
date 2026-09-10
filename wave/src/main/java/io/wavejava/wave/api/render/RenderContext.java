package io.wavejava.wave.api.render;

import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.http.Request;
import java.util.Objects;
import java.util.Optional;

/** Immutable context passed to a {@link Renderer}. */
public final class RenderContext {
    private final Request request;
    private final MediaType mediaType;

    private RenderContext(Request request, MediaType mediaType) {
        this.request = request;
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
    }

    /** Creates a context when rendering is not associated with a request. */
    public static RenderContext of(MediaType mediaType) {
        return new RenderContext(null, mediaType);
    }

    /** Creates a context for a request and its already selected response media type. */
    public static RenderContext of(Request request, MediaType mediaType) {
        return new RenderContext(Objects.requireNonNull(request, "request"), mediaType);
    }

    /** Returns the request when rendering occurs as part of an HTTP invocation. */
    public Optional<Request> request() {
        return Optional.ofNullable(request);
    }

    /** Returns the media type selected before the renderer is invoked. */
    public MediaType mediaType() {
        return mediaType;
    }
}
