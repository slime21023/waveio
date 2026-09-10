package io.wavejava.benchmarks;

import io.wavejava.wave.api.http.HttpMethod;
import io.wavejava.wave.api.http.Request;
import io.wavejava.wave.api.routing.Routes;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** Small deterministic route lookup baseline; transport benchmarks stay in integration suites. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class WaveRouteBenchmark {
    @State(Scope.Thread)
    public static class Fixture {
        final Routes routes = Routes.of(builder -> builder.get("/items/{id}", (request, response) -> response.empty()));
        final Request request = Request.builder().method(HttpMethod.GET).path("/items/42").build();
    }

    @Benchmark
    public Object routeLookup(Fixture fixture) {
        return fixture.routes.match(fixture.request);
    }
}
