package io.waveio.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.execution.ExecutionConfig;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.registry.Registry;
import io.waveio.server.ServerLimits;
import io.waveio.server.ServerSpec;
import io.waveio.server.ServerTimeouts;
import io.waveio.task.Task;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmbeddedServerTest {
    @Test
    void servesThroughThePublicFacade() throws Exception {
        ServerSpec spec = new ServerSpec(new InetSocketAddress("127.0.0.1", 0), context -> {
            context.respond(HttpResponse.of(HttpStatus.OK));
            return Task.success(null);
        }, Registry.empty(), new ExecutionConfig(2, 1, Duration.ofSeconds(1)),
                new ServerLimits(1024, 4096, 1024),
                new ServerTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        try (EmbeddedServer server = EmbeddedServer.start(spec);
                Socket socket = new Socket(server.address().getAddress(), server.address().getPort());
                PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            output.print("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 200 OK"));
        }
    }
}
