package io.waveio.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Internal ordered service lifecycle coordinator. */
final class ServiceLifecycle implements AutoCloseable {
    private final List<Service> started;
    private boolean closed;
    private ServiceLifecycle(List<Service> started) { this.started = started; }

    static ServiceLifecycle start(List<Service> services) {
        List<Service> started = new ArrayList<>();
        try {
            for (Service service : services) {
                Service candidate = Objects.requireNonNull(service, "service");
                candidate.start();
                started.add(candidate);
            }
            return new ServiceLifecycle(started);
        } catch (Exception failure) {
            rollback(started, failure);
            throw new IllegalStateException("service initialization failed", failure);
        }
    }

    @Override public void close() {
        if (closed) { return; }
        closed = true;
        RuntimeException failure = null;
        for (int index = started.size() - 1; index >= 0; index--) {
            try { started.get(index).stop(); } catch (Exception stopFailure) {
                if (failure == null) { failure = new IllegalStateException("service shutdown failed", stopFailure); } else { failure.addSuppressed(stopFailure); }
            }
        }
        if (failure != null) { throw failure; }
    }

    private static void rollback(List<Service> started, Exception initializationFailure) {
        for (int index = started.size() - 1; index >= 0; index--) {
            try { started.get(index).stop(); } catch (Exception rollbackFailure) { initializationFailure.addSuppressed(rollbackFailure); }
        }
    }
}
