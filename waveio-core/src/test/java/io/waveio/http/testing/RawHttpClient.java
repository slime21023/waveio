package io.waveio.http.testing;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Small connection-oriented HTTP/1.1 client for pipelining and malformed-wire tests. */
public final class RawHttpClient implements AutoCloseable {
    private final Socket socket;

    private RawHttpClient(Socket socket) {
        this.socket = socket;
    }

    public static RawHttpClient connect(int port) throws IOException {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5_000);
        return new RawHttpClient(socket);
    }

    public RawHttpClient send(String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        return this;
    }

    public RawHttpResponse readResponse() throws IOException {
        return RawHttpResponse.read(socket);
    }

    public String readHeaders() throws IOException {
        return RawHttpResponse.readWireHeaders(socket);
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
