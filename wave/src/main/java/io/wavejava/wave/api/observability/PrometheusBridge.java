package io.wavejava.wave.api.observability;

import io.wavejava.wave.api.routing.Handler;

/**
 * Optional Prometheus-compatible metrics provider without a core Prometheus dependency.
 *
 * <p>The provider is also a {@link MetricsBridge}; register it with {@link
 * Observability.Builder#metrics(MetricsBridge)} (or {@link Observability.Builder#prometheus}) and
 * explicitly bind {@link #handler()} to the desired protected route. Wave never assumes a metrics
 * path or access policy.</p>
 */
public interface PrometheusBridge extends MetricsBridge {
    /** Returns the provider-owned handler that renders its bounded scrape response. */
    Handler handler();
}
