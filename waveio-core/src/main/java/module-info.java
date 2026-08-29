/**
 * Defines the WaveIO core HTTP/1.1 web micro-framework API.
 *
 * <p>WaveIO provides a high-performance, asynchronous and virtual-thread-native HTTP server runtime
 * built on Netty and Java 21+.
 *
 * <h2>Exported Packages:</h2>
 * <ul>
 *   <li>{@link io.waveio.http}: Core HTTP models (requests, responses, headers, cookies, status codes).</li>
 *   <li>{@link io.waveio.http.body}: Request and response payload representations and pluggable codec SPI.</li>
 *   <li>{@link io.waveio.http.handler}: Synchronous, asynchronous, and reactive streaming handler functional contracts.</li>
 *   <li>{@link io.waveio.http.middleware}: Interceptor chains and pipeline contracts.</li>
 *   <li>{@link io.waveio.http.routing}: Radix tree routing engine, route registries, prefix grouping, and modules.</li>
 *   <li>{@link io.waveio.http.server}: Server lifecycle builder, exception mapping, and observability SPI.</li>
 * </ul>
 */
module io.waveio {
    requires io.netty.buffer;
    requires io.netty.common;
    requires io.netty.codec;
    requires io.netty.codec.http;
    requires io.netty.handler;
    requires io.netty.transport;
    requires static java.net.http;
    // Test-only edge: the runtime logs through System.Logger in java.base, while the diagnostics
    // tests capture those records through a java.util.logging handler.
    requires static java.logging;

    exports io.waveio.http;
    exports io.waveio.http.body;
    exports io.waveio.http.handler;
    exports io.waveio.http.middleware;
    exports io.waveio.http.routing;
    exports io.waveio.http.server;
}
