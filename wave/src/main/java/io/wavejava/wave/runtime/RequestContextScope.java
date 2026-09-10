package io.wavejava.wave.runtime;

import io.wavejava.wave.api.http.RequestContext;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Internal Java 25 {@link ScopedValue} bridge for the current managed request invocation. */
final class RequestContextScope {
    private static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();

    private RequestContextScope() {
    }

    static Optional<RequestContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    static <T> T call(RequestContext context, Callable<T> action) throws Exception {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        return ScopedValue.where(CURRENT, context).call(action::call);
    }
}
