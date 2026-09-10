package io.wavejava.wave.api.client;

import java.util.concurrent.RejectedExecutionException;

/** Thrown when every request slot is in use and the bounded client queue is full. */
public final class ClientRequestPoolRejectedException extends RejectedExecutionException {
    private final int maximumConcurrentRequests;
    private final int maximumQueuedRequests;

    public ClientRequestPoolRejectedException(int maximumConcurrentRequests, int maximumQueuedRequests) {
        super("Client request pool is full: maximumConcurrentRequests=" + maximumConcurrentRequests
                + ", maximumQueuedRequests=" + maximumQueuedRequests);
        this.maximumConcurrentRequests = maximumConcurrentRequests;
        this.maximumQueuedRequests = maximumQueuedRequests;
    }

    /** Returns the active-exchange lease limit. */
    public int maximumConcurrentRequests() {
        return maximumConcurrentRequests;
    }

    /** Returns the bounded waiting-admission limit. */
    public int maximumQueuedRequests() {
        return maximumQueuedRequests;
    }
}



