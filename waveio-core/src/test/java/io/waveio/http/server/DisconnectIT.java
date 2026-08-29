package io.waveio.http.server;

import io.waveio.http.testing.HttpServerITSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.testing.Await;
import io.waveio.http.testing.RawHttpResponse;
import io.waveio.http.testing.TestPublishers;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisconnectIT extends HttpServerITSupport {

    @Test
    void disconnectCancelsActiveStreamingPublisherOverTcp() throws Exception {
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        Flow.Publisher<ByteBuffer> neverEnding = subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override
                    public void request(long amount) {
                        subscribed.countDown();
                    }

                    @Override
                    public void cancel() {
                        cancelled.countDown();
                    }
                });

        try (var streamingServer = HttpServer.builder()
                .port(0)
                .get("/never", request -> HttpResponse.status(HttpStatus.OK).stream(neverEnding))
                .build()
                .start()) {
            var socket = new Socket("127.0.0.1", streamingServer.localPort());
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET /never HTTP/1.1\r\n"
                    + "Host: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            readResponseHeaders(socket);
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));

            socket.close();

            assertTrue(cancelled.await(2, TimeUnit.SECONDS),
                    "stream subscription was not cancelled after disconnect");
        }
    }
}

