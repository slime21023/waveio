package io.wavejava.wave.api.client;

import io.wavejava.wave.api.http.Http2Config;
import java.net.URI;
import java.util.concurrent.CompletionStage;

/** A reusable bounded HTTP client created by {@link io.wavejava.wave.Wave}. */
public interface WaveClient extends AutoCloseable {
    ClientRequestPool requestPool();

    RetryPolicy retryPolicy();

    RedirectPolicy redirectPolicy();

    ProxyPolicy proxyPolicy();

    Http2Config http2();

    ClientTlsConfig tls();

    ClientRequest get(URI uri);

    ClientRequest.Builder request(URI uri);

    ClientResponse execute(ClientRequest request);

    CompletionStage<ClientResponse> executeAsync(ClientRequest request);

    boolean isClosed();

    @Override
    void close();
}
