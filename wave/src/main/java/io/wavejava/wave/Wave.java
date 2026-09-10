package io.wavejava.wave;

import java.util.Objects;

/** Entry point for building a wave application and server. */
public final class Wave {
    private Wave() {
    }

    /** Starts a builder for one immutable application. */
    public static WaveApp.Builder app() {
        return WaveApp.builder();
    }

    /** Creates an unbound server for {@code app}. */
    public static WaveServer server(WaveApp app) {
        return WaveServer.forApp(Objects.requireNonNull(app, "app"));
    }
}
