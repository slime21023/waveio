package io.wavejava.wave.api.render;

import io.wavejava.wave.api.http.MediaType;

/** Renders an application value into a bounded response representation. */
public interface Renderer<T> {
    /** Returns whether this renderer can render {@code type} for the selected media type. */
    boolean supports(Class<?> type, MediaType accepted);

    /** Renders one value. Implementations must not retain mutable request or response state. */
    Rendered render(T value, RenderContext context) throws Exception;
}
