/** Netty HTTP/1.1 transport implementation for WaveIO. */
module io.waveio.netty {
    requires io.netty.buffer;
    requires io.netty.codec;
    requires io.netty.codec.http;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.transport;
    requires transitive io.waveio.http;
}
