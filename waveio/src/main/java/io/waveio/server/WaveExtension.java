package io.waveio.server;

/** Explicitly installed application extension with no access to transport internals. */
@FunctionalInterface
public interface WaveExtension {
    /** Configures services, routes, lifecycle, errors, or observations on one application builder. */
    void configure(WaveApplication.Builder application);
}
