package io.waveio.server;

import java.net.InetSocketAddress;
import java.time.Duration;

/** A started WaveIO server that owns its transport and execution runtime. */
public interface RunningServer extends AutoCloseable {
    /** Returns the actual bound address. */
    InetSocketAddress address();
    /** Stops this server using the supplied explicit grace duration. */
    void stop(Duration grace);
    /** Stops immediately when used in a try-with-resources block. */
    @Override void close();
}
