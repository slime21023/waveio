package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.config.Config;
import io.wavejava.wave.api.config.ConfigSource;
import io.wavejava.wave.api.lifecycle.Service;
import io.wavejava.wave.api.lifecycle.ServiceContext;
import io.wavejava.wave.api.lifecycle.ServiceLifecycleException;
import io.wavejava.wave.api.registry.Registry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ServiceAssemblyIntegrationTest {
    @Test
    void startsServicesBeforeServingARealSocketAndStopsThemInReverseDependencyOrder() throws Exception {
        var events = new ArrayList<String>();
        var contexts = new AtomicReference<ServiceContext>();
        var config = Config.of(ConfigSource.of("defaults", Map.of("region", "tw")));
        var marker = new Object();
        var registry = Registry.builder().add(Object.class, marker).build();
        var database = service("database", Set.of(), events, contexts, null);
        var http = service("http", Set.of("database"), events, contexts, null);
        var app = Wave.app()
                .config(config)
                .registry(registry)
                .service(http)
                .service(database)
                .routes(routes -> routes.get("/ready", (request, response) -> response.text("ready")))
                .build();

        assertSame(config, app.config());
        assertSame(registry, app.registry());
        assertEquals(List.of(http, database), app.services());

        var server = Wave.server(app).listen(0).start();
        try {
            assertEquals(List.of("start:database", "start:http"), events);
            assertSame(config, contexts.get().config());
            assertSame(registry, contexts.get().registry());
            assertEquals("tw", contexts.get().config().require("region"));
            assertSame(marker, contexts.get().registry().require(Object.class));

            var wireResponse = sendGetAndClose(server.port(), "/ready");
            assertTrue(wireResponse.startsWith("HTTP/1.1 200"), wireResponse);
            assertTrue(wireResponse.endsWith("ready"), wireResponse);
            assertTrue(server.isRunning());
        } finally {
            server.close();
        }

        assertEquals(
                List.of("start:database", "start:http", "stop:http", "stop:database"),
                events);
    }

    @Test
    void failedServiceStartupRollsBackAndLeavesTheRequestedSocketPortUnbound() throws Exception {
        var events = new ArrayList<String>();
        var database = service("database", Set.of(), events, new AtomicReference<>(), null);
        var failingCache = new Service() {
            @Override
            public String id() {
                return "cache";
            }

            @Override
            public Set<String> dependencies() {
                return Set.of("database");
            }

            @Override
            public CompletionStage<Void> start(ServiceContext context) {
                events.add("start:cache");
                return CompletableFuture.failedFuture(new IllegalStateException("cache unavailable"));
            }

            @Override
            public CompletionStage<Void> stop() {
                events.add("stop:cache");
                return CompletableFuture.completedFuture(null);
            }
        };
        var app = Wave.app().service(failingCache).service(database).build();
        var port = availablePort();

        var failure = assertThrows(ServiceLifecycleException.class, () -> Wave.server(app).listen(port).start());

        assertEquals(ServiceLifecycleException.Operation.START, failure.operation());
        assertEquals("cache", failure.serviceId().orElseThrow());
        assertEquals(List.of("start:database", "start:cache", "stop:database"), events);
        try (var probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", port));
            assertTrue(probe.isBound(), "a failed service startup must not leave a listener bound");
        }
    }

    private static Service service(
            String id,
            Set<String> dependencies,
            List<String> events,
            AtomicReference<ServiceContext> contexts,
            Throwable stopFailure) {
        return new Service() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> dependencies() {
                return dependencies;
            }

            @Override
            public CompletionStage<Void> start(ServiceContext context) {
                contexts.set(context);
                events.add("start:" + id);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> stop() {
                events.add("stop:" + id);
                return stopFailure == null
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(stopFailure);
            }
        };
    }

    private static int availablePort() throws IOException {
        try (var reservation = new ServerSocket(0)) {
            return reservation.getLocalPort();
        }
    }

    private static String sendGetAndClose(int port, String path) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(5_000);
            var request = "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
