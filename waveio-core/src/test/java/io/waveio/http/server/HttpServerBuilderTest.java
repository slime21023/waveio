package io.waveio.http.server;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.waveio.http.HttpResponse;
import io.waveio.http.routing.Router;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HttpServerBuilderTest {
    private static final Router ROUTER = Router.builder().build();

    @Test
    void rejectsInstallingRouterTwice() {
        assertThrows(IllegalStateException.class,
                () -> HttpServer.builder().router(ROUTER).router(ROUTER));
    }

    @Test
    void rejectsInstallingRouterAfterInlineRoute() {
        var builder = HttpServer.builder().head("/test", request -> HttpResponse.noContent());
        assertThrows(IllegalStateException.class, () -> builder.router(ROUTER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inlineRegistrations")
    void externalRouterRejectsInlineRegistration(String description,
            Consumer<HttpServerBuilder> registration) {
        var builder = HttpServer.builder().router(ROUTER);
        assertThrows(IllegalStateException.class, () -> registration.accept(builder));
    }

    static Stream<Arguments> inlineRegistrations() {
        return Stream.of(
                Arguments.of("HEAD", (Consumer<HttpServerBuilder>) builder ->
                        builder.head("/test", request -> HttpResponse.noContent())),
                Arguments.of("PUT", (Consumer<HttpServerBuilder>) builder ->
                        builder.put("/test", request -> HttpResponse.noContent())),
                Arguments.of("PATCH", (Consumer<HttpServerBuilder>) builder ->
                        builder.patch("/test", request -> HttpResponse.noContent())),
                Arguments.of("DELETE", (Consumer<HttpServerBuilder>) builder ->
                        builder.delete("/test", request -> HttpResponse.noContent())),
                Arguments.of("OPTIONS", (Consumer<HttpServerBuilder>) builder ->
                        builder.options("/test", request -> HttpResponse.noContent())),
                Arguments.of("blocking GET", (Consumer<HttpServerBuilder>) builder ->
                        builder.blockingGet("/test", request -> HttpResponse.noContent())),
                Arguments.of("blocking POST", (Consumer<HttpServerBuilder>) builder ->
                        builder.blockingPost("/test", request -> HttpResponse.noContent())),
                Arguments.of("blocking PUT", (Consumer<HttpServerBuilder>) builder ->
                        builder.blockingPut("/test", request -> HttpResponse.noContent())),
                Arguments.of("blocking PATCH", (Consumer<HttpServerBuilder>) builder ->
                        builder.blockingPatch("/test", request -> HttpResponse.noContent())),
                Arguments.of("blocking DELETE", (Consumer<HttpServerBuilder>) builder ->
                        builder.blockingDelete("/test", request -> HttpResponse.noContent())),
                Arguments.of("async GET", (Consumer<HttpServerBuilder>) builder ->
                        builder.asyncGet("/test", request ->
                                CompletableFuture.completedFuture(HttpResponse.noContent()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfiguration")
    void rejectsInvalidConfigurationAtSetterBoundary(String description,
            Class<? extends Throwable> expected, Executable operation) {
        assertThrows(expected, operation);
    }

    static Stream<Arguments> invalidConfiguration() {
        return Stream.of(
                Arguments.of("blank host", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder().host(" ")),
                Arguments.of("negative port", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder().port(-1)),
                Arguments.of("zero header size", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder().maxHeaderSize(0)),
                Arguments.of("zero pending requests", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder().maxPendingRequestsPerConnection(0)),
                Arguments.of("zero connections", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder().maxConnections(0)),
                Arguments.of("zero handler timeout", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder().handlerTimeout(Duration.ZERO)),
                Arguments.of("zero idle timeout", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder().idleTimeout(Duration.ZERO)),
                Arguments.of("zero write timeout", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder().writeTimeout(Duration.ZERO)),
                Arguments.of("null read timeout", NullPointerException.class,
                        (Executable) () -> HttpServer.builder().readTimeout(null)),
                Arguments.of("null middleware", NullPointerException.class,
                        (Executable) () -> HttpServer.builder().use(null)),
                Arguments.of("null router", NullPointerException.class,
                        (Executable) () -> HttpServer.builder().router(null)),
                Arguments.of("null exception handler", NullPointerException.class,
                        (Executable) () -> HttpServer.builder().exceptionHandler(null)),
                Arguments.of("null observer", NullPointerException.class,
                        (Executable) () -> HttpServer.builder().observe(null)),
                Arguments.of("null certificate", NullPointerException.class,
                        (Executable) () -> HttpServer.builder().tls(null, Path.of("key.pem"))),
                Arguments.of("null private key", NullPointerException.class,
                        (Executable) () -> HttpServer.builder().tls(Path.of("cert.pem"), null)),
                Arguments.of("missing TLS files", IllegalArgumentException.class,
                        (Executable) () -> HttpServer.builder()
                                .tls(Path.of("missing-cert.pem"), Path.of("missing-key.pem"))
                                .build()));
    }
}

