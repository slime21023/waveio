package io.wavejava.wave.api.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Immutable, bounded registration of completion-only observability bridges for one server run.
 *
 * <p>Callbacks are isolated from the HTTP transport by a finite queue. If that queue is full, the
 * newest event is dropped rather than accumulating application work or blocking a Netty EventLoop.
 * A bridge exception is isolated to that callback and never changes an HTTP response.</p>
 */
public final class Observability {
    private static final int MAXIMUM_BRIDGES_PER_KIND = 32;
    private static final Observability DISABLED = new Observability(List.of(), List.of(), List.of(), ObservabilityLimits.defaults());

    private final List<Consumer<AccessLogEvent>> accessLogs;
    private final List<MetricsBridge> metrics;
    private final List<TracingBridge> tracing;
    private final ObservabilityLimits limits;

    private Observability(
            List<Consumer<AccessLogEvent>> accessLogs,
            List<MetricsBridge> metrics,
            List<TracingBridge> tracing,
            ObservabilityLimits limits) {
        this.accessLogs = List.copyOf(accessLogs);
        this.metrics = List.copyOf(metrics);
        this.tracing = List.copyOf(tracing);
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Returns a no-op configuration that does not allocate an observability worker. */
    public static Observability disabled() {
        return DISABLED;
    }

    /** Starts an immutable observability registration. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns whether at least one bridge or access-log sink is configured. */
    public boolean isEnabled() {
        return !accessLogs.isEmpty() || !metrics.isEmpty() || !tracing.isEmpty();
    }

    /** Returns immutable structured access-log callbacks. */
    public List<Consumer<AccessLogEvent>> accessLogs() {
        return accessLogs;
    }

    /** Returns immutable metrics callbacks. */
    public List<MetricsBridge> metrics() {
        return metrics;
    }

    /** Returns immutable tracing callbacks. */
    public List<TracingBridge> tracing() {
        return tracing;
    }

    /** Returns the finite asynchronous event queue policy. */
    public ObservabilityLimits limits() {
        return limits;
    }

    /** Mutable builder with finite callback counts. */
    public static final class Builder {
        private final List<Consumer<AccessLogEvent>> accessLogs = new ArrayList<>();
        private final List<MetricsBridge> metrics = new ArrayList<>();
        private final List<TracingBridge> tracing = new ArrayList<>();
        private ObservabilityLimits limits = ObservabilityLimits.defaults();

        private Builder() {
        }

        /** Adds one structured access-log callback. */
        public Builder accessLog(Consumer<AccessLogEvent> accessLog) {
            add(accessLogs, Objects.requireNonNull(accessLog, "accessLog"), "access logs");
            return this;
        }

        /** Adds one vendor-neutral metrics bridge. */
        public Builder metrics(MetricsBridge metricsBridge) {
            add(metrics, Objects.requireNonNull(metricsBridge, "metricsBridge"), "metrics bridges");
            return this;
        }

        /** Adds one vendor-neutral tracing bridge. */
        public Builder tracing(TracingBridge tracingBridge) {
            add(tracing, Objects.requireNonNull(tracingBridge, "tracingBridge"), "tracing bridges");
            return this;
        }

        /** Adds a Prometheus provider as a metrics bridge; bind its handler explicitly in routes. */
        public Builder prometheus(PrometheusBridge prometheusBridge) {
            return metrics(Objects.requireNonNull(prometheusBridge, "prometheusBridge"));
        }

        /** Replaces the finite queue limit used to isolate bridge callbacks from transports. */
        public Builder limits(ObservabilityLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /** Validates and builds an immutable registration. */
        public Observability build() {
            if (accessLogs.isEmpty() && metrics.isEmpty() && tracing.isEmpty()) {
                return Observability.disabled();
            }
            return new Observability(accessLogs, metrics, tracing, limits);
        }

        private static <T> void add(List<T> target, T item, String label) {
            if (target.size() >= MAXIMUM_BRIDGES_PER_KIND) {
                throw new IllegalStateException(label + " exceed " + MAXIMUM_BRIDGES_PER_KIND);
            }
            target.add(item);
        }
    }
}
