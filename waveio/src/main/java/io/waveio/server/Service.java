package io.waveio.server;

/** A resource whose lifecycle is owned by a WaveIO server. */
public interface Service {
    /** Starts this service before the server accepts traffic. */
    void start() throws Exception;
    /** Stops this service after the server has stopped accepting traffic. */
    void stop() throws Exception;
}
