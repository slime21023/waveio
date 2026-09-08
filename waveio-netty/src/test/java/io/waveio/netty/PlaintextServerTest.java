package io.waveio.netty;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.waveio.execution.ExecutionConfig;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.Headers;
import io.waveio.http.Body;
import io.waveio.registry.Registry;
import io.waveio.task.Task;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test void streamsAChunkedResponseBodyOverTheSocket() throws Exception {
        Body body = Body.of(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean delivered;
            @Override public void request(long count) {
                if (count > 0 && !delivered) {
                    delivered = true;
                    subscriber.onNext(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
                    subscriber.onComplete();
                }
            }
            @Override public void cancel() { }
        }));
        try (PlaintextServer server = PlaintextServer.start(context -> {
            context.respond(new HttpResponse(HttpStatus.OK, Headers.empty(), body));
            return Task.success(null);
        }, Registry.empty(), new ExecutionConfig(8, 1, Duration.ofSeconds(1)));
                Socket socket = new Socket("127.0.0.1", server.port());
                PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            output.print("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 200 OK"));
            assertTrue(input.readLine().equalsIgnoreCase("transfer-encoding: chunked"));
            assertTrue(input.readLine().isEmpty());
            assertTrue(input.readLine().equals("5"));
            assertTrue(input.readLine().equals("hello"));
            assertTrue(input.readLine().equals("0"));
        }
    }

    @Test void servesSequentialResponsesInRequestOrderOnOneConnection() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (PlaintextServer server = PlaintextServer.start(context -> {
            int call = calls.incrementAndGet();
            context.respond(HttpResponse.of(call == 1 ? HttpStatus.OK : HttpStatus.NOT_FOUND));
            return Task.success(null);
        }, Registry.empty(), new ExecutionConfig(8, 1, Duration.ofSeconds(1)));
                Socket socket = new Socket("127.0.0.1", server.port());
                PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            output.print("GET /first HTTP/1.1\r\nHost: localhost\r\n\r\n");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 200 OK"));
            assertTrue(input.readLine().equalsIgnoreCase("content-length: 0"));
            assertTrue(input.readLine().isEmpty());
            output.print("GET /second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 404 Not Found"));
        }
    }
}
