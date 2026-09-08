package io.waveio.server;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.execution.ExecutionConfig;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.registry.Registry;
import io.waveio.task.Task;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ServerSpecTest {
    @Test void rejectsImplicitOrInvalidResourceConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ServerLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ServerTimeouts(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ServerSpec(new InetSocketAddress(0), context -> Task.success(null), Registry.empty(), null, new ServerLimits(1, 1, 1), new ServerTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1))));
    }

    @Test void startsAndStopsThroughThePublicFacade() throws Exception {
        ServerSpec spec = new ServerSpec(new InetSocketAddress("127.0.0.1", 0), context -> {
            context.respond(HttpResponse.of(HttpStatus.OK));
            return Task.success(null);
        }, Registry.empty(), new ExecutionConfig(8, 1, Duration.ofSeconds(1)), new ServerLimits(4096, 8192, 4096), new ServerTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(100)));
        try (RunningServer server = WaveServer.start(spec);
                Socket socket = new Socket(server.address().getAddress(), server.address().getPort());
                PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            output.print("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 200 OK"));
            server.stop(spec.timeouts().shutdownGrace());
        }
    }
}
