package io.wavejava.wave.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.wavejava.wave.Wave;
import org.junit.jupiter.api.Test;

class EmbeddedAppTest {
    @Test
    void startsOnAnEphemeralPortAndServesGetRequestsOverHttp() {
        var application = Wave.app()
                .routes(routes -> routes.get("/hello", (request, response) -> response.text("hello")))
                .build();

        try (var app = EmbeddedApp.start(application)) {
            var response = app.client().get("/hello");

            assertTrue(app.isRunning());
            assertTrue(app.port() > 0);
            assertEquals("http", app.baseUri().getScheme());
            assertEquals(app.baseUri().resolve("/hello"), app.uri("/hello"));
            assertEquals(200, response.statusCode());
            assertEquals(200, response.status());
            assertEquals("hello", response.body());
            assertEquals("text/plain; charset=UTF-8", response.header("Content-Type").orElseThrow());
        }
    }

    @Test
    void postsUtf8TextAndReleasesTheServerWhenClosed() {
        var application = Wave.app()
                .routes(routes -> routes.post("/echo", (request, response) -> response.text(request.body().text())))
                .build();
        var app = EmbeddedApp.start(application);

        var response = app.client().post("/echo", "application/json", "{\"name\":\"wave\"}");
        app.close();

        assertEquals(200, response.status());
        assertEquals("{\"name\":\"wave\"}", response.body());
        assertFalse(app.isRunning());
    }
}

