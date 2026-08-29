package io.waveio.http.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class DefaultHttpServerITSupport extends HttpServerITSupport {
    @BeforeEach
    void startDefaultServer() {
        startServer();
    }

    @AfterEach
    void stopDefaultServer() {
        stopServer();
    }
}
