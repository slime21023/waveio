package io.waveio.consumer.verification;

import io.waveio.server.RunningServer;
import io.waveio.server.Responses;
import io.waveio.server.WaveApplication;
import io.waveio.server.WaveServer;

/** Minimal consumer compiled solely against packaged WaveIO artifacts. */
public final class Main {
    private Main() { }
    /** Starts and stops a public-facade server. */
    public static void main(String[] arguments) {
        WaveApplication application = WaveApplication.builder()
                .routes(routes -> routes.getResponse("/", context -> Responses.text("ok")))
                .build();
        try (RunningServer server = WaveServer.start(0, application)) {
            if (server.address().getPort() < 1) { throw new IllegalStateException("server did not bind"); }
        }
    }
}
