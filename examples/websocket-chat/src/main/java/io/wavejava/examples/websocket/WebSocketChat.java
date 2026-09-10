package io.wavejava.examples.websocket;

import io.wavejava.wave.Wave;
import io.wavejava.wave.api.application.WaveApp;
import io.wavejava.wave.api.websocket.WebSocketMessage;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import java.time.Duration;
import java.util.concurrent.Flow;

/** Bounded WebSocket echo endpoint; application callbacks remain on Wave's invocation executor. */
public final class WebSocketChat {
    private WebSocketChat() {
    }

    public static WaveApp application() {
        return Wave.app().routes(routes -> routes.get("/chat", io.wavejava.wave.api.websocket.WebSocket.handler( session ->
                session.inbound().subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(1);
                    }

                    @Override
                    public void onNext(WebSocketMessage message) {
                        if (message.type() == WebSocketMessage.Type.TEXT
                                || message.type() == WebSocketMessage.Type.BINARY) {
                            session.send(message).whenComplete((ignored, failure) -> {
                                if (failure == null) {
                                    subscription.request(1);
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(Throwable failure) {
                        session.close(1011, "application failure");
                    }

                    @Override
                    public void onComplete() {
                        session.close(1000, "bye");
                    }
                })))).build();
    }

    public static void main(String[] args) {
        try (var server = io.wavejava.wave.Wave.server(application())
                .limits(ServerLimits.builder().maximumConnections(16).maximumInFlightRequests(16).build())
                .timeouts(ServerTimeouts.builder().requestTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofSeconds(30)).writeTimeout(Duration.ofSeconds(30))
                        .idleTimeout(Duration.ofMinutes(1)).shutdownTimeout(Duration.ofSeconds(5)).build())
                .listen(0).start()) {
            System.out.println("websocket example listening on " + server.port() + " (use a ws client)");
        }
    }
}
