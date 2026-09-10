package io.wavejava.wave.runtime;

import java.util.Objects;

/**
 * Per-connection accounting for HTTP responses that have been admitted but not fully written.
 *
 * <p>A slot is acquired when a request enters the connection pipeline. Its byte reservation is
 * acquired only after application code has produced a response, and both are released when that
 * response's transport write completes. This makes a delayed first HTTP/1.1 response visible as
 * both an in-flight request count and a bounded amount of buffered response data.</p>
 *
 * <p>This type deliberately has no transport dependency. The adapter owns the actual queued
 * buffers and supplies their retained byte count to the sequencer.</p>
 */
public final class PendingResponseBudget {
    /** The capacity dimension that prevented an additional reservation. */
    public enum Rejection {
        PENDING_RESPONSE_COUNT,
        BUFFERED_RESPONSE_BYTES
    }

    /** Immutable observation of the budget after an operation. */
    public record Snapshot(
            int pendingResponses,
            long pendingResponseBytes,
            int maximumPendingResponses,
            long maximumPendingResponseBytes
    ) {
        /** Returns whether a transport adapter should pause additional reads. */
        public boolean shouldPauseReads() {
            return pendingResponses >= maximumPendingResponses
                    || pendingResponseBytes >= maximumPendingResponseBytes;
        }

        /** Returns the number of request slots currently available for ingress. */
        public int availableResponseSlots() {
            return maximumPendingResponses - pendingResponses;
        }

        /** Returns the number of response bytes that can be retained without exceeding the cap. */
        public long availableResponseBytes() {
            return maximumPendingResponseBytes - pendingResponseBytes;
        }
    }

    /** The result of trying to reserve one budget dimension. */
    public record Decision(boolean accepted, Rejection rejection, Snapshot snapshot) {
        public Decision {
            Objects.requireNonNull(snapshot, "snapshot");
            if (accepted && rejection != null) {
                throw new IllegalArgumentException("an accepted reservation cannot have a rejection reason");
            }
            if (!accepted && rejection == null) {
                throw new IllegalArgumentException("a rejected reservation must have a rejection reason");
            }
        }

        /** Returns whether this result represents a capacity rejection. */
        public boolean rejected() {
            return !accepted;
        }
    }

    private final int maximumPendingResponses;
    private final long maximumPendingResponseBytes;
    private int pendingResponses;
    private long pendingResponseBytes;

    /**
     * Creates a budget with positive count and byte caps.
     *
     * @param maximumPendingResponses maximum admitted responses awaiting transport completion
     * @param maximumPendingResponseBytes maximum retained response bytes across that connection
     */
    public PendingResponseBudget(int maximumPendingResponses, long maximumPendingResponseBytes) {
        if (maximumPendingResponses <= 0) {
            throw new IllegalArgumentException("maximumPendingResponses must be positive");
        }
        if (maximumPendingResponseBytes <= 0) {
            throw new IllegalArgumentException("maximumPendingResponseBytes must be positive");
        }
        this.maximumPendingResponses = maximumPendingResponses;
        this.maximumPendingResponseBytes = maximumPendingResponseBytes;
    }

    /** Attempts to reserve one admitted request/response slot. */
    public synchronized Decision tryAcquireResponse() {
        if (pendingResponses >= maximumPendingResponses) {
            return rejected(Rejection.PENDING_RESPONSE_COUNT);
        }
        pendingResponses++;
        return accepted();
    }

    /**
     * Attempts to reserve the exact number of bytes retained for one completed response.
     *
     * <p>The method never changes accounting on rejection. A caller that receives a rejection
     * must not retain the corresponding payload in an unbounded side queue.</p>
     */
    public synchronized Decision tryReserveResponseBytes(long responseBytes) {
        validateResponseBytes(responseBytes);
        if (responseBytes > maximumPendingResponseBytes - pendingResponseBytes) {
            return rejected(Rejection.BUFFERED_RESPONSE_BYTES);
        }
        pendingResponseBytes += responseBytes;
        return accepted();
    }

    /**
     * Releases one response slot and the byte reservation made for its completed response.
     *
     * @param responseBytes the same byte count passed to {@link #tryReserveResponseBytes(long)}
     * @return the resulting budget state
     */
    public synchronized Snapshot releaseResponse(long responseBytes) {
        validateResponseBytes(responseBytes);
        if (pendingResponses == 0) {
            throw new IllegalStateException("no pending response slot to release");
        }
        if (responseBytes > pendingResponseBytes) {
            throw new IllegalStateException("response byte release exceeds the reserved response bytes");
        }
        pendingResponses--;
        pendingResponseBytes -= responseBytes;
        return snapshot();
    }

    /** Returns an atomic snapshot for adapters deciding whether to enable {@code autoRead}. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                pendingResponses,
                pendingResponseBytes,
                maximumPendingResponses,
                maximumPendingResponseBytes
        );
    }

    private Decision accepted() {
        return new Decision(true, null, snapshot());
    }

    private Decision rejected(Rejection rejection) {
        return new Decision(false, rejection, snapshot());
    }

    private static void validateResponseBytes(long responseBytes) {
        if (responseBytes < 0) {
            throw new IllegalArgumentException("responseBytes must not be negative");
        }
    }
}
