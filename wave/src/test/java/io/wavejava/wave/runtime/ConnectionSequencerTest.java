package io.wavejava.wave.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionSequencerTest {
    @Test
    void emitsOnlyContiguousResponsesInIngressOrder() {
        var sequencer = new ConnectionSequencer<String>(3, 64);
        var first = accepted(sequencer.tryAdmit());
        var second = accepted(sequencer.tryAdmit());
        var third = accepted(sequencer.tryAdmit());

        var thirdCompletedFirst = sequencer.complete(third, "third", 2);
        assertTrue(thirdCompletedFirst.accepted());
        assertTrue(thirdCompletedFirst.ready().isEmpty());
        assertEquals(1, thirdCompletedFirst.snapshot().completedResponses());
        assertTrue(thirdCompletedFirst.snapshot().shouldPauseReads());

        var firstCompleted = sequencer.complete(first, "first", 1);
        assertEquals(List.of(0L), sequences(firstCompleted.ready()));
        assertEquals(List.of("first"), payloads(firstCompleted.ready()));
        assertEquals(1, firstCompleted.snapshot().completedResponses());
        assertEquals(1, firstCompleted.snapshot().emittedResponses());

        var firstWritten = sequencer.acknowledgeWritten(first);
        assertTrue(firstWritten.released());
        assertFalse(firstWritten.snapshot().shouldPauseReads());
        assertEquals(2, firstWritten.snapshot().budget().pendingResponseBytes());

        var secondCompleted = sequencer.complete(second, "second", 3);
        assertEquals(List.of(1L, 2L), sequences(secondCompleted.ready()));
        assertEquals(List.of("second", "third"), payloads(secondCompleted.ready()));

        sequencer.acknowledgeWritten(second);
        var thirdWritten = sequencer.acknowledgeWritten(third);
        assertEquals(0, thirdWritten.snapshot().budget().pendingResponses());
        assertEquals(0, thirdWritten.snapshot().budget().pendingResponseBytes());
        assertEquals(3, thirdWritten.snapshot().nextAcknowledgementSequence());
    }

    @Test
    void countCapRejectsIngressWithoutConsumingASequenceAndResumesAfterWrite() {
        var sequencer = new ConnectionSequencer<String>(2, 32);
        var first = accepted(sequencer.tryAdmit());
        accepted(sequencer.tryAdmit());

        var rejected = sequencer.tryAdmit();
        assertFalse(rejected.accepted());
        assertEquals(ConnectionSequencer.Rejection.PENDING_RESPONSE_COUNT, rejected.rejection());
        assertTrue(rejected.snapshot().shouldPauseReads());

        var firstReady = sequencer.complete(first, "first", 1);
        assertEquals(List.of(0L), sequences(firstReady.ready()));
        var released = sequencer.acknowledgeWritten(first);
        assertFalse(released.snapshot().shouldPauseReads());

        var admittedAfterRelease = sequencer.tryAdmit();
        assertTrue(admittedAfterRelease.accepted());
        assertEquals(2L, admittedAfterRelease.ticket().sequence());
    }

    @Test
    void byteOverflowRequiresAbortAndReturnsOnlyInternallyBufferedPayloads() {
        var sequencer = new ConnectionSequencer<String>(3, 10);
        var first = accepted(sequencer.tryAdmit());
        var second = accepted(sequencer.tryAdmit());

        assertTrue(sequencer.complete(second, "later", 8).ready().isEmpty());
        var overflow = sequencer.complete(first, "first", 3);

        assertFalse(overflow.accepted());
        assertEquals(ConnectionSequencer.Rejection.BUFFERED_RESPONSE_BYTES, overflow.rejection());
        assertEquals(ConnectionSequencer.State.ABORT_REQUIRED, overflow.snapshot().state());
        assertTrue(overflow.snapshot().shouldPauseReads());
        assertEquals(8, overflow.snapshot().budget().pendingResponseBytes());
        assertEquals(ConnectionSequencer.Rejection.ABORT_REQUIRED, sequencer.tryAdmit().rejection());

        var aborted = sequencer.abort();
        assertEquals(List.of("later"), aborted.bufferedPayloads());
        assertEquals(ConnectionSequencer.State.CLOSED, aborted.snapshot().state());
        assertEquals(0, aborted.snapshot().budget().pendingResponses());
        assertEquals(0, aborted.snapshot().budget().pendingResponseBytes());
        assertTrue(aborted.snapshot().shouldPauseReads());
        assertEquals(ConnectionSequencer.Rejection.CLOSED, sequencer.complete(first, "discarded", 0).rejection());
    }

    @Test
    void acknowledgementsMustFollowTheOrderedWriteStream() {
        var sequencer = new ConnectionSequencer<String>(2, 10);
        var first = accepted(sequencer.tryAdmit());
        var second = accepted(sequencer.tryAdmit());

        sequencer.complete(first, "first", 1);
        sequencer.complete(second, "second", 1);

        assertThrows(IllegalStateException.class, () -> sequencer.complete(first, "duplicate", 0));
        assertThrows(IllegalStateException.class, () -> sequencer.acknowledgeWritten(second));
        sequencer.acknowledgeWritten(first);
        sequencer.acknowledgeWritten(second);
    }

    private static ConnectionSequencer.Ticket accepted(ConnectionSequencer.Admission admission) {
        assertTrue(admission.accepted(), () -> "unexpected rejection: " + admission.rejection());
        return admission.ticket();
    }

    private static List<Long> sequences(List<ConnectionSequencer.Ready<String>> ready) {
        return ready.stream().map(value -> value.ticket().sequence()).toList();
    }

    private static List<String> payloads(List<ConnectionSequencer.Ready<String>> ready) {
        return ready.stream().map(ConnectionSequencer.Ready::payload).toList();
    }
}
