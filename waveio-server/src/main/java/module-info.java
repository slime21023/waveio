/** Public WaveIO server configuration facade. */
module io.waveio.server {
    requires transitive io.waveio.execution;
    requires transitive io.waveio.http;
    requires io.waveio.netty;
    exports io.waveio.server;
}
