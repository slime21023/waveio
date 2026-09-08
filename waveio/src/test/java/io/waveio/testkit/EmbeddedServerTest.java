package io.waveio.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waveio.server.Responses;
import io.waveio.server.WaveApplication;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EmbeddedServerTest {
    @Test
    void servesThroughThePublicFacade() throws Exception {
        WaveApplication application = WaveApplication.builder()
                .routes(routes -> routes.getResponse("/", context -> Responses.text("ok")))
                .build();
        try (EmbeddedServer server = EmbeddedServer.start(application);
                Socket socket = new Socket(server.address().getAddress(), server.address().getPort());
                PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
            output.print("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            output.flush();
            assertTrue(input.readLine().startsWith("HTTP/1.1 200 OK"));
        }
    }
}
