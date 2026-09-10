package io.wavejava.wave.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orders concurrent HTTP/1.1 response completion by request ingress sequence.
 *
 * <p>An adapter calls {@link #tryAdmit()} on ingress, dispatches application work using the
 * returned {@link Ticket}, and calls {@link #complete(Ticket, Object, long)} after encoding a
 * response. Each completion returns only responses whose predecessors have already completed.
 * The adapter writes each returned {@link Ready} in list order and calls
 * {@link #acknowledgeWritten(Ticket)} from its write-completion callback.</p>
 *
 * <p>When the request count cap is reached, ingress is temporarily rejected and
 * {@link Snapshot#shouldPauseReads()} becomes true. A byte-cap rejection is terminal for this
 * connection: retaining a further out-of-order response would violate the configured bound, so
 * the adapter must stop reads, close or otherwise abort the connection, and call {@link #abort()}.
 * The rejected payload remains owned by the caller and is never retained by this type.</p>
 *
 * <p>All methods are synchronized so completion producers may safely race. An adapter should
 * still serialize actual writes on its connection event loop.</p>
 *
 * @param <T> framework-owned response representation; it must not be {@code null}
 */
public final class ConnectionSequencer<T> {
    /** Lifecycle state visible to the transport adapter. */
    public enum State {
        OPEN,
        ABORT_REQUIRED,
        CLOSED
    }

    /** Why an ingress or completion operation was not accepted. */
    public enum Rejection {
        PENDING_RESPONSE_COUNT,
        BUFFERED_RESPONSE_BYTES,
        SEQUENCE_EXHAUSTED,
        ABORT_REQUIRED,
        CLOSED
    }

    /** Opaque token identifying a request's ingress position on one connection. */
    public static final class Ticket {
        private final long sequence;

        private Ticket(long sequence) {
            this.sequence = sequence;
        }

        /** Returns this request's monotonically increasing connection-local sequence. */
        public long sequence() {
            return sequence;
        }
    }

    /** Result of request ingress admission. {@code ticket} is null only on rejection. */
    public record Admission(Ticket ticket, Rejection rejection, Snapshot snapshot) {
        public Admission {
            Objects.requireNonNull(snapshot, "snapshot");
            if ((ticket == null) == (rejection == null)) {
                throw new IllegalArgumentException("admission must contain exactly one of ticket or rejection");
            }
        }

        /** Returns whether application work may be dispatched for this ingress request. */
        public boolean accepted() {
            return ticket != null;
        }
    }

    /** A completed response which may now be written without violating HTTP/1.1 order. */
    public record Ready<T>(Ticket ticket, T payload, long responseBytes) {
        public Ready {
            Objects.requireNonNull(ticket, "ticket");
            Objects.requireNonNull(payload, "payload");
            if (responseBytes < 0) {
                throw new IllegalArgumentException("responseBytes must not be negative");
            }
        }
    }

    /** Result of application response completion. */
    public record Completion<T>(List<Ready<T>> ready, Rejection rejection, Snapshot snapshot) {
        public Completion {
            ready = List.copyOf(Objects.requireNonNull(ready, "ready"));
            Objects.requireNonNull(snapshot, "snapshot");
            if (rejection != null && !ready.isEmpty()) {
                throw new IllegalArgumentException("a rejected completion cannot make responses ready");
            }
        }

        /** Returns whether the response was accepted into the bounded sequence. */
        public boolean accepted() {
            return rejection == null;
        }
    }

    /** Result of one transport write acknowledgement. */
    public record WriteAcknowledgement(boolean released, Snapshot snapshot) {
        public WriteAcknowledgement {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** Payloads retained by the sequencer and handed back when a connection is aborted. */
    public record Abort<T>(List<T> bufferedPayloads, Snapshot snapshot) {
        public Abort {
            bufferedPayloads = List.copyOf(Objects.requireNonNull(bufferedPayloads, "bufferedPayloads"));
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** Full state required for deterministic backpressure decisions. */
    public record Snapshot(
            State state,
            long nextIngressSequence,
            long nextWriteSequence,
            long nextAcknowledgementSequence,
            int completedResponses,
            int emittedResponses,
            PendingResponseBudget.Snapshot budget
    ) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(budget, "budget");
        }

        /** Returns whether the adapter must pause further connection reads. */
        public boolean shouldPauseReads() {
            return state != State.OPEN || budget.shouldPauseReads();
        }
    }

    private final PendingResponseBudget budget;
    private final Map<Long, Entry<T>> outstanding = new HashMap<>();
    private final Map<Long, Entry<T>> completed = new HashMap<>();
    private final Map<Long, Entry<T>> emitted = new HashMap<>();

    private State state = State.OPEN;
    private long nextIngressSequence;
    private long nextWriteSequence;
    private long nextAcknowledgementSequence;

    /** Creates a sequencer with explicit per-connection request and byte limits. */
    public ConnectionSequencer(int maximumPendingResponses, long maximumPendingResponseBytes) {
        budget = new PendingResponseBudget(maximumPendingResponses, maximumPendingResponseBytes);
    }

    /**
     * Attempts to assign the next ingress sequence and reserve its response slot.
     *
     * <p>A count rejection is temporary: call this again after write acknowledgements free slots.
     * No sequence number is consumed on rejection.</p>
     */
    public synchronized Admission tryAdmit() {
        if (state == State.ABORT_REQUIRED) {
            return rejectedAdmission(Rejection.ABORT_REQUIRED);
        }
        if (state == State.CLOSED) {
            return rejectedAdmission(Rejection.CLOSED);
        }
        if (nextIngressSequence == Long.MAX_VALUE) {
            state = State.ABORT_REQUIRED;
            return rejectedAdmission(Rejection.SEQUENCE_EXHAUSTED);
        }

        var decision = budget.tryAcquireResponse();
        if (decision.rejected()) {
            return rejectedAdmission(Rejection.PENDING_RESPONSE_COUNT);
        }

        var ticket = new Ticket(nextIngressSequence++);
        outstanding.put(ticket.sequence(), new Entry<>(ticket));
        return new Admission(ticket, null, snapshot());
    }

    /**
     * Stores one completed response and returns every newly unblocked response in write order.
     *
     * @param ticket the ticket returned from {@link #tryAdmit()}
     * @param payload framework-owned response data; ownership transfers to the returned
     *                {@link Ready} only when completion is accepted
     * @param responseBytes bytes retained by the sequencer or transport until write completion
     */
    public synchronized Completion<T> complete(Ticket ticket, T payload, long responseBytes) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(payload, "payload");
        validateResponseBytes(responseBytes);

        if (state == State.ABORT_REQUIRED) {
            return rejectedCompletion(Rejection.ABORT_REQUIRED);
        }
        if (state == State.CLOSED) {
            return rejectedCompletion(Rejection.CLOSED);
        }

        var entry = outstanding.get(ticket.sequence());
        if (entry == null || entry.ticket != ticket) {
            throw new IllegalArgumentException("ticket does not belong to this sequencer: " + ticket.sequence());
        }
        if (entry.state != EntryState.AWAITING_COMPLETION) {
            throw new IllegalStateException("response has already completed for ticket " + ticket.sequence());
        }

        var byteDecision = budget.tryReserveResponseBytes(responseBytes);
        if (byteDecision.rejected()) {
            state = State.ABORT_REQUIRED;
            return rejectedCompletion(Rejection.BUFFERED_RESPONSE_BYTES);
        }

        entry.payload = payload;
        entry.responseBytes = responseBytes;
        entry.state = EntryState.COMPLETED;
        completed.put(ticket.sequence(), entry);
        return new Completion<>(drainReady(), null, snapshot());
    }

    /**
     * Releases a response's slot and byte reservation after its ordered transport write completes.
     *
     * <p>Acknowledgements must occur in the same order as {@link Ready} values were handed to the
     * adapter. A callback received after {@link #abort()} is ignored and returns
     * {@code released == false}.</p>
     */
    public synchronized WriteAcknowledgement acknowledgeWritten(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (state == State.CLOSED) {
            return new WriteAcknowledgement(false, snapshot());
        }
        if (ticket.sequence() != nextAcknowledgementSequence) {
            throw new IllegalStateException("writes must be acknowledged in ingress order; expected "
                    + nextAcknowledgementSequence + " but received " + ticket.sequence());
        }

        var entry = emitted.remove(ticket.sequence());
        if (entry == null) {
            throw new IllegalStateException("ticket has not been emitted for writing: " + ticket.sequence());
        }
        outstanding.remove(ticket.sequence());
        budget.releaseResponse(entry.responseBytes);
        nextAcknowledgementSequence++;
        return new WriteAcknowledgement(true, snapshot());
    }

    /**
     * Closes the sequencer and releases all accounting. Buffered, not-yet-emitted payloads are
     * returned to the adapter for any transport-specific cleanup it requires.
     */
    public synchronized Abort<T> abort() {
        if (state == State.CLOSED) {
            return new Abort<>(List.of(), snapshot());
        }

        var bufferedPayloads = new ArrayList<T>();
        for (Entry<T> entry : completed.values()) {
            if (entry.payload != null) {
                bufferedPayloads.add(entry.payload);
            }
        }
        for (Entry<T> entry : outstanding.values()) {
            budget.releaseResponse(entry.responseBytes);
        }
        outstanding.clear();
        completed.clear();
        emitted.clear();
        state = State.CLOSED;
        return new Abort<>(bufferedPayloads, snapshot());
    }

    /** Returns an atomic state snapshot without changing ingress or write state. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                state,
                nextIngressSequence,
                nextWriteSequence,
                nextAcknowledgementSequence,
                completed.size(),
                emitted.size(),
                budget.snapshot()
        );
    }

    private List<Ready<T>> drainReady() {
        var ready = new ArrayList<Ready<T>>();
        Entry<T> entry;
        while ((entry = completed.remove(nextWriteSequence)) != null) {
            entry.state = EntryState.EMITTED;
            emitted.put(entry.ticket.sequence(), entry);
            var payload = entry.payload;
            entry.payload = null;
            ready.add(new Ready<>(entry.ticket, Objects.requireNonNull(payload, "payload"), entry.responseBytes));
            nextWriteSequence++;
        }
        return List.copyOf(ready);
    }

    private Admission rejectedAdmission(Rejection rejection) {
        return new Admission(null, rejection, snapshot());
    }

    private Completion<T> rejectedCompletion(Rejection rejection) {
        return new Completion<>(List.of(), rejection, snapshot());
    }

    private static void validateResponseBytes(long responseBytes) {
        if (responseBytes < 0) {
            throw new IllegalArgumentException("responseBytes must not be negative");
        }
    }

    private enum EntryState {
        AWAITING_COMPLETION,
        COMPLETED,
        EMITTED
    }

    private static final class Entry<T> {
        private final Ticket ticket;
        private EntryState state = EntryState.AWAITING_COMPLETION;
        private T payload;
        private long responseBytes;

        private Entry(Ticket ticket) {
            this.ticket = ticket;
        }
    }
}
