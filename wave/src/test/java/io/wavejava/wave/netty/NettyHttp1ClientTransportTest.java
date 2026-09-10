package io.wavejava.wave.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.api.client.ClientRequestPool;
import io.wavejava.wave.api.client.ClientRequest;
import io.wavejava.wave.api.client.ProxyPolicy;
import io.wavejava.wave.testing.MockUpstream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Deterministic physical-channel-cap contracts for the private Netty transport. */
class NettyHttp1ClientTransportTest {
    @Test
    void evictsIdleChannelsAcrossDistinctOriginsInsteadOfGrowingTheRouteCache() throws Exception {
        var pool = ClientRequestPool.builder().maximumConcurrentRequests(1).build();
        try (var first = MockUpstream.start(request -> MockUpstream.Response.text(200, "first"));
                var second = MockUpstream.start(request -> MockUpstream.Response.text(200, "second"));
                var third = MockUpstream.start(request -> MockUpstream.Response.text(200, "third"));
                var transport = new NettyHttp1ClientTransport(pool, ProxyPolicy.direct())) {
            for (var upstream : List.of(first, second, third)) {
                var response = transport.send(ClientRequest.get(upstream.baseUri()))
                        .completion()
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals(200, response.status());
                var snapshot = transport.snapshot();
                assertTrue(snapshot.liveChannels() <= 1, () -> "live channels: " + snapshot);
                assertTrue(snapshot.idleChannels() <= 1, () -> "idle channels: " + snapshot);
                assertEquals(0, snapshot.pendingStarts(), () -> "pending starts: " + snapshot);
            }
            assertEquals(new NettyHttp1ClientTransport.TransportSnapshot(1, 1, 0), transport.snapshot());
            first.assertHealthy();
            second.assertHealthy();
            third.assertHealthy();
        } finally {
            pool.close();
        }
    }
}


