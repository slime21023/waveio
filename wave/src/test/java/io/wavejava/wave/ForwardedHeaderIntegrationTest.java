package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.wavejava.wave.api.server.ForwardedHeaderPolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ForwardedHeaderIntegrationTest {
    @Test
    void trustedProxyPolicyExposesOnlyValidatedPublicAddressToApplicationCode() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/origin", (request, response) -> response.text(
                request.publicAddress().map(Object::toString).orElse("none")))).build();
        var policy = ForwardedHeaderPolicy.builder().trustedProxy("127.0.0.1/32").build();

        try (var server = Wave.server(app).forwardedHeaders(policy).listen(0).start()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/origin"))
                    .header("Forwarded", "for=192.0.2.2;proto=https;host=public.example:8443")
                    .GET()
                    .build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("https://public.example:8443", response.body());
        }
    }
}
