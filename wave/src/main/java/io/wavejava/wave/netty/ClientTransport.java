package io.wavejava.wave.netty;

import io.wavejava.wave.api.client.ClientRequest;
import io.wavejava.wave.api.client.ClientRequestPool;
import io.wavejava.wave.api.client.ClientTlsConfig;
import io.wavejava.wave.api.client.ProxyPolicy;
import io.wavejava.wave.api.http.Headers;
import io.wavejava.wave.api.server.Http2Config;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Private seam between the exported client orchestration and its owned transport implementation.
 *
 * <p>No instance of this type is exposed from {@link WaveClient}; it exists so policy, admission,
 * and cancellation orchestration stay independent of Netty implementation classes. A transport
 * exchange has a separate logical cancellation handle and physical completion stage.</p>
 */
public interface ClientTransport extends AutoCloseable {
    /** Starts one finite byte-body exchange. */
    Exchange send(ClientRequest request);

    /** Begins transport shutdown and completes after all physical channels have terminated. */
    CompletionStage<Void> closeAsync();

    @Override
    void close();

    /** The cancellation and physical-terminal state for one transport exchange. */
    interface Exchange {
        /** Returns the physical lifecycle completion used to release a client-pool lease. */
        CompletionStage<TransportResponse> completion();

        /** Returns the private cancellation action registered with the logical client exchange. */
        CancellationBridge.CancellationHandle cancellationHandle();
    }

    /** Immutable response snapshot detached from transport buffers and channel state. */
    record TransportResponse(int status, Headers headers, byte[] body) {
        public TransportResponse {
            headers = Objects.requireNonNull(headers, "headers");
            body = Objects.requireNonNull(body, "body").clone();
        }
    }

    /** Creates the owned bounded Netty transport selected by the explicit HTTP protocol policy. */
    static ClientTransport netty(
            ClientRequestPool requestPool,
            ProxyPolicy proxyPolicy,
            Http2Config http2,
            ClientTlsConfig tls) {
        if (!http2.isEnabled()) {
            return new NettyClientTransport(requestPool, proxyPolicy, tls);
        }
        return new NettyHttp2ClientTransport(requestPool, proxyPolicy, http2, tls);
    }
}


