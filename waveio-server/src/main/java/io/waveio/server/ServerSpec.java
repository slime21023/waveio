package io.waveio.server;

import io.waveio.execution.ExecutionConfig;
import io.waveio.http.Handler;
import io.waveio.registry.Registry;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/** Immutable, fully explicit description of a server that may be started by WaveServer. */
public record ServerSpec(InetSocketAddress address, Handler handler, Registry registry, ExecutionConfig executionConfig, ServerLimits limits, ServerTimeouts timeouts, List<Service> services) {
    /** Validates all required server assembly inputs. */
    public ServerSpec {
        address = Objects.requireNonNull(address, "address");
        handler = Objects.requireNonNull(handler, "handler");
        registry = Objects.requireNonNull(registry, "registry");
        executionConfig = Objects.requireNonNull(executionConfig, "executionConfig");
        limits = Objects.requireNonNull(limits, "limits");
        timeouts = Objects.requireNonNull(timeouts, "timeouts");
        services = List.copyOf(Objects.requireNonNull(services, "services"));
    }
    /** Creates a server specification without owned services. */
    public ServerSpec(InetSocketAddress address, Handler handler, Registry registry, ExecutionConfig executionConfig, ServerLimits limits, ServerTimeouts timeouts) { this(address, handler, registry, executionConfig, limits, timeouts, List.of()); }
}
