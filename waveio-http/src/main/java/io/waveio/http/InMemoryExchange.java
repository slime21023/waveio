package io.waveio.http;

import io.waveio.execution.ExecutionConfig;
import io.waveio.execution.ExecutionRuntime;
import io.waveio.registry.Registry;
import java.time.Duration;
import java.util.Objects;

/** Memory-only handler fixture using the production Task and execution runtime model. */
public final class InMemoryExchange {
    private InMemoryExchange() { }
    /** Invokes one handler and returns its committed response. */
    public static HttpResponse execute(HttpRequest request, Registry registry, Handler handler, ExecutionConfig config) throws Exception {
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(registry, "registry"); Objects.requireNonNull(handler, "handler"); Objects.requireNonNull(config, "config");
        ResponseTransaction response = new ResponseTransaction();
        Context context = new DefaultContext(request, registry, new InMemoryChain(java.util.List.of()), response);
        try (ExecutionRuntime runtime = ExecutionRuntime.create(config)) { handler.handle(context).run(runtime).toCompletableFuture().get(); }
        return response.committed().orElseThrow(() -> new IllegalStateException("handler completed without a response"));
    }
    /** Invokes a handler chain and returns its committed response. */
    public static HttpResponse execute(HttpRequest request, Registry registry, java.util.List<Handler> handlers, ExecutionConfig config) throws Exception {
        Objects.requireNonNull(handlers, "handlers");
        return execute(request, registry, context -> new InMemoryChain(handlers).next(context), config);
    }
    /** Provides a small explicit config suitable for deterministic in-memory examples. */
    public static ExecutionConfig defaultConfig() { return new ExecutionConfig(8, 1, Duration.ofSeconds(1)); }
}
