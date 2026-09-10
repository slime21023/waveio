package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.health.HealthEndpoints;
import io.wavejava.wave.api.health.HealthRegistry;
import io.wavejava.wave.api.health.HealthStatus;
import io.wavejava.wave.api.lifecycle.Service;
import io.wavejava.wave.api.lifecycle.ServiceContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class HealthEndpointIntegrationTest {
    @Test
    void explicitHealthEndpointsFollowServerStartupAndShutdownLifecycle() throws Exception {
        var health = HealthRegistry.builder().check(new io.wavejava.wave.api.health.HealthCheck() {
            @Override
            public String name() {
                return "dependency";
            }

            @Override
            public CompletionStage<HealthStatus> check(io.wavejava.wave.api.health.HealthCheckContext context) {
                return CompletableFuture.completedFuture(HealthStatus.up("dependency available"));
            }
        }).build();
        var app = Wave.app().routes(routes -> routes
                .get("/live", HealthEndpoints.liveness(health))
                .get("/ready", HealthEndpoints.readiness(health)))
                .build();
        var client = HttpClient.newHttpClient();

        var server = Wave.server(app).health(health).listen(0).start();
        try {
            assertEquals(200, get(client, server.port(), "/live").statusCode());
            assertEquals(200, get(client, server.port(), "/ready").statusCode());
            assertTrue(health.readiness().isUp());
        } finally {
            server.close();
        }
        assertFalse(health.liveness().isUp());
        assertFalse(health.readiness().isUp());
    }

    @Test
    void serviceStartupFailureMarksReadinessDownBeforeTheListenerBinds() {
        var health = HealthRegistry.builder().build();
        var failing = new Service() {
            @Override
            public CompletionStage<Void> start(ServiceContext context) {
                return CompletableFuture.failedFuture(new IllegalStateException("intentional startup failure"));
            }

            @Override
            public CompletionStage<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }
        };
        var app = Wave.app().service(failing).build();

        assertThrows(RuntimeException.class, () -> Wave.server(app).health(health).listen(0).start());
        assertEquals(HealthRegistry.LifecycleState.FAILED, health.lifecycleState());
        assertFalse(health.readiness().isUp());
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
