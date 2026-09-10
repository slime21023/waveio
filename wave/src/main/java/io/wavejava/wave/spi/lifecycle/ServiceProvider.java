package io.wavejava.wave.spi.lifecycle;

import io.wavejava.wave.api.lifecycle.Service;
import io.wavejava.wave.spi.ProviderContext;
import io.wavejava.wave.spi.WaveProvider;

/** ServiceLoader factory for one framework lifecycle {@link Service}. */
public interface ServiceProvider extends WaveProvider {
    /** Creates a fresh service instance for one Wave server run. */
    Service create(ProviderContext context) throws Exception;
}
