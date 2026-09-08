package io.waveio.consumer.verification;

import io.waveio.execution.ExecutionConfig;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.registry.Registry;
import io.waveio.server.RunningServer;
import io.waveio.server.ServerLimits;
import io.waveio.server.ServerSpec;
import io.waveio.server.ServerTimeouts;
import io.waveio.server.WaveServer;
import io.waveio.task.Task;
import java.net.InetSocketAddress;
import java.time.Duration;

/** Minimal consumer compiled solely against packaged WaveIO artifacts. */
public final class Main {
    private Main() { }
    /** Starts and stops a public-facade server. */
    public static void main(String[] arguments) {
        ServerSpec spec = new ServerSpec(new InetSocketAddress("127.0.0.1", 0), context -> {
            context.respond(HttpResponse.of(HttpStatus.OK));
            return Task.success(null);
        }, Registry.empty(), new ExecutionConfig(2, 1, Duration.ofSeconds(1)),
                new ServerLimits(1024, 4096, 1024),
                new ServerTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        try (RunningServer server = WaveServer.start(spec)) {
            if (server.address().getPort() < 1) { throw new IllegalStateException("server did not bind"); }
        }
    }
}
