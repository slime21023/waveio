package io.wavejava.wave.api.lifecycle;

import java.util.Objects;
import java.util.Optional;

/** Identifies a service lifecycle operation that failed. */
public final class ServiceLifecycleException extends IllegalStateException {
    /** Lifecycle operation in which the failure occurred. */
    public enum Operation {
        START,
        STOP
    }

    private final Operation operation;
    private final String serviceId;

    ServiceLifecycleException(Operation operation, String serviceId, String message, Throwable cause) {
        super(message, cause);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.serviceId = serviceId;
    }

    /** Returns whether the failed operation was startup or shutdown. */
    public Operation operation() {
        return operation;
    }

    /** Returns the affected service ID, when this exception represents one service. */
    public Optional<String> serviceId() {
        return Optional.ofNullable(serviceId);
    }

    static ServiceLifecycleException start(String serviceId, Throwable cause) {
        return new ServiceLifecycleException(
                Operation.START,
                serviceId,
                "Service '" + serviceId + "' failed during startup: " + describe(cause),
                cause);
    }

    static ServiceLifecycleException stop(String serviceId, Throwable cause) {
        return new ServiceLifecycleException(
                Operation.STOP,
                serviceId,
                "Service '" + serviceId + "' failed during shutdown: " + describe(cause),
                cause);
    }

    static ServiceLifecycleException aggregateStop(Throwable firstFailure) {
        return new ServiceLifecycleException(
                Operation.STOP,
                null,
                "One or more services failed during shutdown",
                firstFailure);
    }

    private static String describe(Throwable failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return failure == null ? "unknown failure" : failure.getClass().getSimpleName();
        }
        return failure.getMessage();
    }
}
