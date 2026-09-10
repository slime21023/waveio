package io.wavejava.wave.runtime;

/**
 * Bounded accounting for bytes handed to a transport write but not yet acknowledged.
 *
 * <p>The budget owns accounting only, never a byte array or Netty buffer. A streaming adapter
 * reserves bytes before it schedules a write and releases exactly that reservation from the write
 * completion callback. Closing the budget drops all accounting so late callbacks are harmless.
 * This makes the one-item Flow bridge bounded even while a peer stops reading.</p>
 */
public final class OutboundByteBudget {
    /** Immutable state for deterministic budget tests and adapter decisions. */
    public record Snapshot(long maximumBytes, long reservedBytes, long availableBytes, boolean closed) {
        public Snapshot {
            if (maximumBytes <= 0) {
                throw new IllegalArgumentException("maximumBytes must be greater than zero");
            }
            if (reservedBytes < 0 || reservedBytes > maximumBytes) {
                throw new IllegalArgumentException("reservedBytes must be within the configured budget");
            }
            if (availableBytes != maximumBytes - reservedBytes) {
                throw new IllegalArgumentException("availableBytes must equal maximumBytes minus reservedBytes");
            }
        }
    }

    /** Result of attempting to reserve a finite byte range. */
    public record Reservation(boolean accepted, Snapshot snapshot) {
        public Reservation {
            if (snapshot == null) {
                throw new NullPointerException("snapshot");
            }
        }
    }

    private final long maximumBytes;
    private long reservedBytes;
    private boolean closed;

    /** Creates a budget with a strictly positive byte cap. */
    public OutboundByteBudget(long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be greater than zero: " + maximumBytes);
        }
        this.maximumBytes = maximumBytes;
    }

    /** Attempts to reserve {@code bytes} until a write completion releases it. */
    public synchronized Reservation tryReserve(long bytes) {
        validateBytes(bytes);
        if (closed || bytes > maximumBytes - reservedBytes) {
            return new Reservation(false, snapshot());
        }
        reservedBytes += bytes;
        return new Reservation(true, snapshot());
    }

    /**
     * Releases a prior reservation.
     *
     * @return {@code true} when the reservation was released, or {@code false} after closure
     */
    public synchronized boolean release(long bytes) {
        validateBytes(bytes);
        if (closed) {
            return false;
        }
        if (bytes > reservedBytes) {
            throw new IllegalStateException("Cannot release " + bytes + " bytes from " + reservedBytes + " reserved bytes");
        }
        reservedBytes -= bytes;
        return true;
    }

    /** Releases all accounting and rejects any later reservation. */
    public synchronized Snapshot close() {
        closed = true;
        reservedBytes = 0;
        return snapshot();
    }

    /** Returns the current finite budget state. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(maximumBytes, reservedBytes, maximumBytes - reservedBytes, closed);
    }

    private static void validateBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative: " + bytes);
        }
    }
}
