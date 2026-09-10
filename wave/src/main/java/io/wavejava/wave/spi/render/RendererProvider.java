package io.wavejava.wave.spi.render;

import io.wavejava.wave.api.render.Renderer;
import io.wavejava.wave.spi.WaveProvider;

/** ServiceLoader provider for one public response {@link Renderer}. */
public interface RendererProvider extends WaveProvider {
    /** Returns the immutable renderer instance supplied by this provider. */
    Renderer<?> renderer();
}
