package io.wavejava.examples.gateway;

import io.wavejava.wave.Wave;
import io.wavejava.wave.WaveApp;
import io.wavejava.wave.api.client.WaveClient;
import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import java.net.URI;
import java.time.Duration;

/** Small gateway example using the bounded Wave client and explicit upstream timeout. */
public final class GatewayApi {
    private GatewayApi() {
    }

    public static WaveApp application(URI upstream) {
        return Wave.app().routes(routes -> routes.get("/proxy", (request, response) -> {
            try (var client = WaveClient.create()) {
                var upstreamResponse = client.request(upstream).timeout(java.time.Duration.ofSeconds(2)).build();
                var result = client.execute(upstreamResponse);
                response.bytes(result.body(), MediaType.APPLICATION_OCTET_STREAM);
            }
        })).build();
    }

    public static void main(String[] args) {
        try (var server = Wave.server(application(URI.create("http://127.0.0.1:9/")))
                .limits(ServerLimits.builder().maximumConnections(16).maximumInFlightRequests(16).build())
                .timeouts(ServerTimeouts.builder().requestTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(5)).writeTimeout(Duration.ofSeconds(5))
                        .idleTimeout(Duration.ofSeconds(10)).shutdownTimeout(Duration.ofSeconds(5)).build())
                .listen(0).start()) {
            System.out.println("gateway example listening on " + server.port());
        }
    }
}
