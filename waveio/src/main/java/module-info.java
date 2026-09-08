/** WaveIO public HTTP application framework. */
module io.waveio {
    requires io.netty.buffer;
    requires io.netty.codec;
    requires io.netty.codec.http;
    requires io.netty.common;
    requires io.netty.handler;
    requires io.netty.transport;

    exports io.waveio.registry;
    exports io.waveio.execution;
    exports io.waveio.task;
    exports io.waveio.http;
    exports io.waveio.server;
    exports io.waveio.testkit;
}
