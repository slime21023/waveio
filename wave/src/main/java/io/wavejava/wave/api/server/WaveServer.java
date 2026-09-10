package io.wavejava.wave.api.server;

import io.wavejava.wave.api.health.HealthRegistry;
import io.wavejava.wave.api.http.Http2Config;
import io.wavejava.wave.api.observability.Observability;

/** Configures one bounded server before its listener is started. */
public interface WaveServer {
    WaveServer listen(int port);

    WaveServer limits(ServerLimits limits);

    WaveServer timeouts(ServerTimeouts timeouts);

    WaveServer tls(TlsConfig tls);

    WaveServer forwardedHeaders(ForwardedHeaderPolicy forwardedHeaders);

    WaveServer http2(Http2Config http2);

    WaveServer health(HealthRegistry health);

    WaveServer observability(Observability observability);

    WaveServer compression(boolean compression);

    WaveServer requestBodyMode(RequestBodyMode requestBodyMode);

    WaveServer requestStreaming(boolean enabled);

    WaveServer maximumRequestBodyBytes(long maximumRequestBodyBytes);

    RunningServer start();
}
