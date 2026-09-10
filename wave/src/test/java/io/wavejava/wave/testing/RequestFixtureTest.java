package io.wavejava.wave.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.wavejava.wave.Wave;
import io.wavejava.wave.internal.http.ResponseDataReader;
import org.junit.jupiter.api.Test;

class RequestFixtureTest {
    @Test
    void createsFreshSingleReadBodiesForRepeatedInMemoryDispatch() {
        var app = Wave.app().routes(routes -> routes.post("/echo", (request, response) -> response.text(request.body().text())))
                .build();
        var fixture = RequestFixture.request().method("POST").target("/echo").body("wave");

        var first = fixture.send(app);
        var second = fixture.send(app);

        assertEquals("wave", new String(ResponseDataReader.read(first).byteContent().orElseThrow()));
        assertEquals("wave", new String(ResponseDataReader.read(second).byteContent().orElseThrow()));
    }
}

