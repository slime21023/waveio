package io.wavejava.wave.runtime;

import io.wavejava.wave.api.http.Request;
import java.util.function.Consumer;

/** Internal transport-facing operation that dispatches one request and returns an application result. */
@FunctionalInterface
public interface RequestDispatcher {
    /**
     * Dispatches one request outside a transport EventLoop and reports the safe route label once
     * middleware has permitted route matching.
     */
    ApplicationResult dispatch(Request request, Consumer<String> routeObserver);
}

