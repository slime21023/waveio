package io.waveio.http.benchmark;

import io.waveio.http.HttpHeaders;
import io.waveio.http.HttpMethod;
import io.waveio.http.HttpRequest;
import io.waveio.http.HttpResponse;
import io.waveio.http.body.RequestBody;
import io.waveio.http.internal.RequestTargetParser;
import io.waveio.http.routing.Router;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class CoreBenchmark {
    private final Router router = Router.builder()
            .get("/health", request -> HttpResponse.text("ok"))
            .get("/users/:id", request -> HttpResponse.text(request.requirePathParam("id")))
            .get("/assets/*path", request -> HttpResponse.noContent())
            .build();
    private final HttpRequest request = new HttpRequest(HttpMethod.GET, "/users/42",
            HttpHeaders.empty(), RequestBody.empty(), null, Map.of(), Map.of());

    @Benchmark
    public Object routeMatch() {
        return router.match(request).orElseThrow();
    }

    @Benchmark
    public Object strictRequestTargetParse() {
        return RequestTargetParser.parse("/caf%C3%A9/items%20one?tag=a+b&tag=c%2Bd");
    }
}
