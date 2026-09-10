package io.wavejava.examples.streaming;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.api.http.BodyPublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Flow;

/** Minimal response-stream example with one item and explicit Flow demand. */
public final class StreamingApi {
    private StreamingApi() {
    }

    public static WaveApp application() {
        return Wave.app().routes(routes -> routes.get("/stream", (request, response) ->
                response.stream(BodyPublisher.from(new OneItemPublisher("bounded stream"))))).build();
    }

    public static void main(String[] args) throws Exception {
        try (var server = server(); var client = HttpClient.newHttpClient()) {
            var target = URI.create("http://127.0.0.1:" + server.port() + "/stream");
            var response = client.send(HttpRequest.newBuilder(target).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.body().equals("bounded stream")) {
                throw new IllegalStateException("streaming example smoke failed");
            }
            System.out.println(response.body());
        }
    }

    private static io.wavejava.wave.api.server.RunningServer server() {
        return Wave.server(application())
                .limits(ServerLimits.builder().maximumConnections(16).maximumInFlightRequests(16)
                        .maximumOutboundStreamBytesPerConnection(64 * 1024).build())
                .timeouts(ServerTimeouts.builder().requestTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(5)).writeTimeout(Duration.ofSeconds(5))
                        .idleTimeout(Duration.ofSeconds(10)).shutdownTimeout(Duration.ofSeconds(5)).build())
                .listen(0)
                .start();
    }

    private static final class OneItemPublisher implements Flow.Publisher<ByteBuffer> {
        private final byte[] bytes;

        private OneItemPublisher(String value) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean sent;

                @Override
                public void request(long demand) {
                    if (demand <= 0) {
                        subscriber.onError(new IllegalArgumentException("demand must be positive"));
                    } else if (!sent) {
                        sent = true;
                        subscriber.onNext(ByteBuffer.wrap(bytes));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    sent = true;
                }
            });
        }
    }
}
