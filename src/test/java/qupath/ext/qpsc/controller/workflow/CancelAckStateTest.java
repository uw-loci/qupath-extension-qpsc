package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.controller.workflow.CancelAckState.Phase;

/** The progress dialog waits for the run to confirm a cancel instead of closing itself. */
class CancelAckStateTest {

    @Test
    void waitsUntilAcknowledged() {
        AtomicLong clock = new AtomicLong(1000);
        CancelAckState ack = new CancelAckState(clock::get, 60_000);
        assertEquals(Phase.REQUESTED, ack.phase());
        assertFalse(ack.mayClose());
        assertEquals(CancelAckState.WAITING_TEXT, ack.statusText("done"));
        clock.addAndGet(30_000);
        assertFalse(ack.mayClose(), "half the timeout: still waiting");
        ack.acknowledge();
        assertEquals(Phase.ACKNOWLEDGED, ack.phase());
        assertTrue(ack.mayClose());
        assertEquals("done", ack.statusText("done"));
    }

    @Test
    void timesOutAndPointsAtTheStopControl() {
        AtomicLong clock = new AtomicLong(0);
        CancelAckState ack = new CancelAckState(clock::get, 60_000);
        clock.set(60_000);
        assertEquals(Phase.TIMED_OUT, ack.phase());
        assertTrue(ack.mayClose());
        assertTrue(ack.statusText("done").contains("Stop acquisition"));
        ack.acknowledge();
        assertEquals(Phase.TIMED_OUT, ack.phase(), "a late acknowledgement does not rewrite history");
    }
}
