package io.wavejava.wave.runtime.client;

import io.wavejava.wave.api.client.ClientRequest;
import io.wavejava.wave.api.http.Headers;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Private seam between the exported client orchestration and its owned transport implementation.
 *
 * <p>No instance of this type is exposed from the public client contract; it exists so policy, admission,
 * and cancellation orchestration stay independent of Netty implementation classes. A transport
 * exchange has a separate logical cancellation handle and physical completion stage.</p>
 */
public interface ClientTransport extends AutoCloseable {
    /** Starts one finite byte-body exchange. */
    Exchange send(ClientRequest request);

    /** Begins transport shutdown and completes after all physical channels have terminated. */
    CompletionStage<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().toCompletableFuture().join();
    }

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

}


