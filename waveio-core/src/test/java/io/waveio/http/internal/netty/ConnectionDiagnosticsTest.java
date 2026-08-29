package io.waveio.http.internal.netty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.waveio.http.HttpResponse;
import io.waveio.http.HttpStatus;
import io.waveio.http.routing.Router;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * A connection the framework closes on its own must say why. Without this the only symptom an
 * operator sees is a client reporting that the connection dropped.
 */
class ConnectionDiagnosticsTest {

    @Test
    void reportsWhyAPipelineOverflowClosedTheConnection() {
        try (var captured = capture(NettyRequestHandler.class)) {
            var router = Router.builder()
                    .blockingGet("/work", request -> HttpResponse.text("done"))
                    .build();
            var channel = new EmbeddedChannel(new NettyRequestHandler(router, List.of(),
                    (failure, request) -> HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .build(),
                    Executors.newSingleThreadExecutor(),
                    ConnectionOptions.defaults().withMaxPendingRequests(2)));

            for (int index = 0; index < 4; index++) channel.writeInbound(request());
            channel.runPendingTasks();

            assertFalse(channel.isOpen());
            assertTrue(captured.mentions("pending"), captured.messages().toString());
        }
    }

    @Test
    void reportsWhyAdmissionRejectedAConnection() {
        try (var captured = capture(ConnectionAdmissionHandler.class)) {
            var admission = new ConnectionAdmissionHandler(1);
            var admitted = new EmbeddedChannel(admission);
            var rejected = new EmbeddedChannel(admission);

            assertTrue(admitted.isActive());
            assertFalse(rejected.isActive());
            assertTrue(captured.mentions("connection"), captured.messages().toString());

            admitted.close();
            rejected.finishAndReleaseAll();
        }
    }

    private static Captured capture(Class<?> source) {
        return new Captured(source.getName());
    }

    /** Collects records from one logger, restoring its previous level on close. */
    private static final class Captured implements AutoCloseable {
        private final Logger logger;
        private final Level previousLevel;
        private final boolean previousUseParent;
        private final Handler handler;
        private final List<LogRecord> records = new ArrayList<>();

        private Captured(String name) {
            logger = Logger.getLogger(name);
            previousLevel = logger.getLevel();
            previousUseParent = logger.getUseParentHandlers();
            handler = new Handler() {
                @Override public void publish(LogRecord record) { records.add(record); }
                @Override public void flush() {}
                @Override public void close() {}
            };
            handler.setLevel(Level.ALL);
            logger.setLevel(Level.ALL);
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
        }

        List<String> messages() {
            return records.stream().map(LogRecord::getMessage).toList();
        }

        boolean mentions(String fragment) {
            return messages().stream()
                    .anyMatch(message -> message != null
                            && message.toLowerCase(java.util.Locale.ROOT)
                                    .contains(fragment.toLowerCase(java.util.Locale.ROOT)));
        }

        @Override public void close() {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousUseParent);
        }
    }

    private static DefaultFullHttpRequest request() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/work");
    }
}
