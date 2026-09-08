package io.waveio.netty;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.waveio.execution.ExecutionConfig;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.registry.Registry;
import io.waveio.task.Task;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

    class PlaintextServerTest {
    @Test void servesAHandlerResponseOverARealSocket() throws Exception {
        try (PlaintextServer server = PlaintextServer.start(context -> {
            context.respond(HttpResponse.of(HttpStatus.OK));
            return Task.success(null);
        }, Registry.empty(), new ExecutionConfig(8, 1, Duration.ofSeconds(1)));
                Socket socket = new Socket("127.0.0.1", server.port());
                PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            output.print("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 200 OK"));
            assertTrue(input.readLine().equalsIgnoreCase("content-length: 0"));
        }
    }

    @Test void readsTheRequestBodyOnlyThroughTheContextPublisher() throws Exception {
        try (PlaintextServer server = PlaintextServer.start(context -> Task.fromStage(context.body().collect(3)).map(bytes -> {
            assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), bytes);
            context.respond(HttpResponse.of(HttpStatus.OK));
            return null;
        }), Registry.empty(), new ExecutionConfig(8, 1, Duration.ofSeconds(1)));
                Socket socket = new Socket("127.0.0.1", server.port());
                PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            output.print("POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 200 OK"));
        }
    }
}
