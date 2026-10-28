package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Any of the three cancel signals stops the loop, and the log names which one. */
class CancelGateTest {

    @Test
    void quietGateIsNotCancelled() {
        CancelGate gate = new CancelGate(() -> false, () -> false, () -> false);
        assertFalse(gate.isCancelled());
        assertEquals("none", gate.source());
    }

    @Test
    void eachSignalCancelsOnItsOwn() {
        assertTrue(new CancelGate(() -> true, () -> false, () -> false).isCancelled());
        assertTrue(new CancelGate(() -> false, () -> true, () -> false).isCancelled());
        assertTrue(new CancelGate(() -> false, () -> false, () -> true).isCancelled());
        assertEquals("progress dialog", new CancelGate(() -> true, () -> false, () -> false).source());
        assertEquals("stop request", new CancelGate(() -> false, () -> true, () -> false).source());
        assertEquals("batch abort", new CancelGate(() -> false, () -> false, () -> true).source());
    }

    @Test
    void aStopRequestedAfterTheGateWasBuiltIsStillSeen() {
        // The gate is built once per run and consulted before every stage-driving step; the
        // 2026-10-27 cancel landed between the dialog opening and the ACQUIRE send.
        AtomicBoolean stop = new AtomicBoolean(false);
        CancelGate gate = new CancelGate(() -> false, stop::get, () -> false);
        assertFalse(gate.isCancelled(), "before the idle wait");
        stop.set(true);
        assertTrue(gate.isCancelled(), "after the idle wait, before the send");
    }
}
