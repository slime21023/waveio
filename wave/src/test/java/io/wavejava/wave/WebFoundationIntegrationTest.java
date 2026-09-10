package io.wavejava.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.wavejava.wave.api.http.MediaType;
import io.wavejava.wave.api.http.Problem;
import io.wavejava.wave.api.http.Cookie;
import io.wavejava.wave.api.render.ContentNegotiationResolver;
import io.wavejava.wave.api.render.RenderContext;
import io.wavejava.wave.api.render.Rendered;
import io.wavejava.wave.api.render.Renderer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Real-socket contracts joining the 0.3 rendering and aggregate-form public APIs. */
class WebFoundationIntegrationTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void selectsAnAcceptedRendererAndCommitsItsBoundedOutput() throws Exception {
        var resolver = new ContentNegotiationResolver();
        var renderer = new GreetingRenderer();
        var app = Wave.app().routes(routes -> routes.get("/greeting", (request, response) -> {
            var selected = resolver.resolve(request.headers(), List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
            if (selected.isEmpty()) {
                response.problem(Problem.of(406, "Not Acceptable"));
                return;
            }
            var mediaType = selected.orElseThrow();
            if (!renderer.supports(Greeting.class, mediaType)) {
                response.problem(Problem.of(500, "Renderer Misconfigured"));
                return;
            }
            renderer.render(new Greeting("wave"), RenderContext.of(request, mediaType)).writeTo(response);
        })).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var target = URI.create("http://127.0.0.1:" + server.port() + "/greeting");
            var text = send(HttpRequest.newBuilder(target)
                    .header("Accept", "application/json;q=0.2, text/plain;q=0.8")
                    .GET()
                    .build());
            var json = send(HttpRequest.newBuilder(target)
                    .header("Accept", "application/json")
                    .GET()
                    .build());
            var rejected = send(HttpRequest.newBuilder(target)
                    .header("Accept", "image/png")
                    .GET()
                    .build());

            assertEquals(200, text.statusCode());
            assertEquals("text/plain; charset=UTF-8", text.headers().firstValue("content-type").orElseThrow());
            assertEquals("hello wave", text.body());
            assertEquals(200, json.statusCode());
            assertEquals("application/json", json.headers().firstValue("content-type").orElseThrow());
            assertEquals("{\"message\":\"hello wave\"}", json.body());
            assertEquals(406, rejected.statusCode());
        }
    }

    @Test
    void parsesOneBoundedAggregateFormOverTheHttpAdapter() throws Exception {
        var app = Wave.app().routes(routes -> routes.post("/contact", (request, response) -> {
            var form = new io.wavejava.wave.api.form.UrlEncodedFormParser().parse(request.body());
            response.text(form.first("name").orElseThrow() + ':' + String.join(",", form.values("tag")));
        })).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var target = URI.create("http://127.0.0.1:" + server.port() + "/contact");
            var response = send(HttpRequest.newBuilder(target)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString("name=Ada+Lovelace&tag=java&tag=web", StandardCharsets.UTF_8))
                    .build());

            assertEquals(200, response.statusCode());
            assertEquals("Ada Lovelace:java,web", response.body());
        }
    }

    @Test
    void transportsRequestAndResponseCookiesWithoutExposingNettyHeaders() throws Exception {
        var app = Wave.app().routes(routes -> routes.get("/session", (request, response) -> {
            var session = request.cookies().stream()
                    .filter(cookie -> cookie.name().equals("session"))
                    .findFirst()
                    .map(Cookie::value)
                    .orElse("missing");
            response.cookie(Cookie.builder("session", "rotated")
                    .path("/")
                    .httpOnly(true)
                    .sameSite(Cookie.SameSite.LAX)
                    .build())
                    .text(session);
        })).build();

        try (var server = Wave.server(app).listen(0).start()) {
            var target = URI.create("http://127.0.0.1:" + server.port() + "/session");
            var response = send(HttpRequest.newBuilder(target)
                    .header("Cookie", "theme=dark; session=abc")
                    .GET()
                    .build());

            assertEquals(200, response.statusCode());
            assertEquals("abc", response.body());
            assertEquals("session=rotated; Path=/; HttpOnly; SameSite=Lax",
                    response.headers().firstValue("set-cookie").orElseThrow());
        }
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private record Greeting(String name) {
    }

    private static final class GreetingRenderer implements Renderer<Greeting> {
        @Override
        public boolean supports(Class<?> type, MediaType accepted) {
            return type.equals(Greeting.class)
                    && (accepted.equals(MediaType.APPLICATION_JSON) || accepted.equals(MediaType.TEXT_PLAIN));
        }

        @Override
        public Rendered render(Greeting value, RenderContext context) {
            var message = "hello " + value.name();
            if (context.mediaType().equals(MediaType.TEXT_PLAIN)) {
                return Rendered.text(message);
            }
            return Rendered.of(("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8),
                    MediaType.APPLICATION_JSON);
        }
    }
}
