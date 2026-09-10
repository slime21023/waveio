package io.wavejava.wave.spi.render;

import io.wavejava.wave.api.render.Parser;
import io.wavejava.wave.spi.WaveProvider;

/** ServiceLoader provider for one public request-body {@link Parser}. */
public interface ParserProvider extends WaveProvider {
    /** Returns the immutable parser instance supplied by this provider. */
    Parser<?> parser();
}
