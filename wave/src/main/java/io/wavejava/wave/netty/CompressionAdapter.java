package io.wavejava.wave.netty;

import io.netty.handler.codec.http.HttpContentCompressor;

/** Internal HTTP/1.1 response-compression boundary. */
final class CompressionAdapter {
    private CompressionAdapter() {
    }

    /** Returns the transport handler used when compression is explicitly enabled. */
    static HttpContentCompressor create() {
        return new HttpContentCompressor();
    }
}
