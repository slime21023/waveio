package io.wavejava.wave.runtime.server;

import io.wavejava.wave.api.http.Http2Config;
import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import io.wavejava.wave.api.server.RequestBodyMode;
import io.wavejava.wave.api.server.RunningServer;
import io.wavejava.wave.api.server.ServerLimits;
import io.wavejava.wave.api.server.ServerTimeouts;
import io.wavejava.wave.api.server.TlsConfig;

/** Unexported boundary implemented by the built-in server transport. */
public interface ServerTransport {
    RunningServer start(
            ServerRuntime runtime,
            int port,
            ServerLimits limits,
            ServerTimeouts timeouts,
            TlsConfig tls,
            ForwardedHeaderPolicy forwardedHeaders,
            Http2Config http2,
            boolean compression,
            RequestBodyMode requestBodyMode);
}
