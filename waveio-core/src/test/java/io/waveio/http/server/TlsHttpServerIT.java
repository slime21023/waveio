package io.waveio.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.waveio.http.HttpResponse;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsHttpServerIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void servesHttpsRejectsPlaintextAndReleasesAdmissionAfterHandshakeFailure()
            throws Exception {
        var certificate = resourcePath("/tls/localhost-cert.pem");
        var privateKey = resourcePath("/tls/localhost-key.pem");
        try (var server = HttpServer.builder()
                .port(0)
                .maxConnections(1)
                .tls(certificate, privateKey)
                .get("/secure", request -> HttpResponse.text("secure"))
                .build()
                .start()) {
            try (var plaintext = new Socket("127.0.0.1", server.localPort())) {
                plaintext.setSoTimeout(5_000);
                plaintext.getOutputStream().write(
                        "GET /secure HTTP/1.1\r\nHost: localhost\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                assertEquals(-1, plaintext.getInputStream().read());
            }

            var client = HttpClient.newBuilder()
                    .sslContext(trusting(certificate))
                    .build();
            var response = client.send(java.net.http.HttpRequest.newBuilder(
                            URI.create("https://localhost:" + server.localPort() + "/secure"))
                    .GET().build(), BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("secure", response.body());
        }
    }

    @Test
    void rejectsInvalidPemBeforeBindingTheServer() throws Exception {
        var certificate = temporaryDirectory.resolve("invalid-cert.pem");
        var privateKey = temporaryDirectory.resolve("invalid-key.pem");
        java.nio.file.Files.writeString(certificate, "not a certificate");
        java.nio.file.Files.writeString(privateKey, "not a key");
        try (var server = HttpServer.builder()
                .port(0)
                .tls(certificate, privateKey)
                .get("/", request -> HttpResponse.text("unexpected"))
                .build()) {
            assertThrows(IllegalArgumentException.class, server::start);
            assertFalse(server.isRunning());
        }
    }

    private static Path resourcePath(String name) throws Exception {
        return Path.of(TlsHttpServerIT.class.getResource(name).toURI());
    }

    private static SSLContext trusting(Path certificatePath) throws Exception {
        var certificates = CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate certificate;
        try (InputStream input = java.nio.file.Files.newInputStream(certificatePath)) {
            certificate = certificates.generateCertificate(input);
        }
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("waveio-test", certificate);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
