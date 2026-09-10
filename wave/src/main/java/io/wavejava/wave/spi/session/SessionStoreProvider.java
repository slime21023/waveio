package io.wavejava.wave.spi.session;

import io.wavejava.wave.api.session.SessionStore;
import io.wavejava.wave.spi.ProviderContext;
import io.wavejava.wave.spi.WaveProvider;

/** ServiceLoader factory for a bounded public {@link SessionStore}. */
public interface SessionStoreProvider extends WaveProvider {
    /** Creates one store from immutable application assembly state. */
    SessionStore create(ProviderContext context) throws Exception;
}
