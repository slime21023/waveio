package io.waveio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaveApplicationTest {
    @Test
    void profilesResolveBoundedSettingsAndRejectInvalidExplicitSettings() {
        assertTrue(ServerProfile.DEVELOPMENT.options().execution().queueCapacity() > 0);
        assertThrows(IllegalArgumentException.class, () -> new ServerLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ServerTimeouts(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ServerOptions(null, ServerProfile.TESTING.options().limits(),
                ServerProfile.TESTING.options().timeouts(), ServerProfile.TESTING.options().observations()));
    }

    @Test
    void startsAndStopsAHighLevelApplicationThroughThePublicFacade() throws Exception {
        WaveApplication application = WaveApplication.builder()
                .routes(routes -> routes.getResponse("/", context -> Responses.text("hello")))
                .build();
        ServerOptions options = ServerProfile.TESTING.options();
        try (RunningServer server = WaveServer.start(new InetSocketAddress("127.0.0.1", 0), application, options);
                Socket socket = new Socket(server.address().getAddress(), server.address().getPort());
                PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            assertEquals(options, server.options());
            output.print("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 200 OK"));
        }
    }

    @Test
    void installsExtensionsInRegistrationOrderAndMakesBuildersSingleUse() {
        List<String> events = new ArrayList<>();
        WaveExtension first = application -> application.service(new RecordingService("first", events, false));
        WaveExtension second = application -> application.service(new RecordingService("second", events, false));
        WaveApplication.Builder builder = WaveApplication.builder().install(first).install(second);
        WaveApplication application = builder.build();
        assertThrows(IllegalStateException.class, builder::build);
        ServiceLifecycle lifecycle = ServiceLifecycle.start(application.services());
        lifecycle.close();
        assertEquals(List.of("start:first", "start:second", "stop:second", "stop:first"), events);
    }

    @Test
    void rollsBackStartedServicesWhenInitializationFails() {
        List<String> events = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> ServiceLifecycle.start(List.of(
                new RecordingService("first", events, false), new RecordingService("second", events, true))));
        assertEquals(List.of("start:first", "start:second", "stop:first"), events);
    }

    @Test
    void rendersTextAndUsesOnlyExactRendererClasses() {
        assertEquals("text/plain; charset=utf-8", Responses.text("hello").headers().first("content-type"));
        Renderer<String> stringRenderer = new Renderer<>() {
            @Override public Class<String> type() { return String.class; }
            @Override public io.waveio.http.HttpResponse render(String value) { return Responses.text(value); }
        };
        assertEquals(io.waveio.http.HttpStatus.OK, Renderers.of(List.of(stringRenderer)).render("hello").status());
        assertThrows(IllegalArgumentException.class, () -> Renderers.of(List.of(stringRenderer)).render(new StringBuilder("hello")));
    }

    private static final class RecordingService implements Service {
        private final String name;
        private final List<String> events;
        private final boolean failStart;

        RecordingService(String name, List<String> events, boolean failStart) {
            this.name = name;
            this.events = events;
            this.failStart = failStart;
        }

        @Override public void start() {
            events.add("start:" + name);
            if (failStart) {
                throw new IllegalStateException("start failure");
            }
        }

        @Override public void stop() {
            events.add("stop:" + name);
        }
    }
}
