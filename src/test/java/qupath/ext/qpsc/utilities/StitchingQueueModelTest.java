package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.StitchingQueueModel.Job;
import qupath.ext.qpsc.utilities.StitchingQueueModel.State;

/** The stitching queue as data: order, states, outputs and timing. */
class StitchingQueueModelTest {

    @Test
    void aJobGoesQueuedRunningDoneInOrder() {
        AtomicLong clock = new AtomicLong(1_000);
        StitchingQueueModel m = new StitchingQueueModel(clock::get);
        m.enqueue("L004 - Tissue_1", "L004 - Tissue_1");
        m.enqueue("L004 - Tissue_2", "L004 - Tissue_2");
        assertEquals(
                List.of("L004 - Tissue_1", "L004 - Tissue_2"),
                m.jobs().stream().map(Job::id).toList());
        assertEquals(2, m.activeCount());
        assertEquals("2 queued", m.summary());

        clock.set(5_000);
        m.start("L004 - Tissue_1", "L004 - Tissue_1");
        m.updateStatus("L004 - Tissue_1", "stitching 90.0...");
        Job running = m.job("L004 - Tissue_1");
        assertEquals(State.RUNNING, running.state());
        assertEquals("stitching 90.0...", running.status());
        assertEquals(0, running.elapsedSeconds(5_000));
        assertEquals(7, running.elapsedSeconds(12_000));
        assertEquals("1 stitching, 1 queued", m.summary());

        m.recordOutput("L004 - Tissue_1", "L004_lcps_20x_001.ome.tif");
        m.recordOutput("L004 - Tissue_1", "L004_lcps_20x_001.ome.tif");
        clock.set(65_000);
        m.complete("L004 - Tissue_1");
        Job done = m.job("L004 - Tissue_1");
        assertEquals(State.DONE, done.state());
        assertEquals(List.of("L004_lcps_20x_001.ome.tif"), done.outputs(), "outputs are recorded once");
        assertEquals(60, done.elapsedSeconds(999_999), "elapsed freezes at completion");
        assertEquals(1, m.activeCount());
        assertFalse(m.isIdle());
    }

    @Test
    void failureKeepsTheReasonAndTheRow() {
        StitchingQueueModel m = new StitchingQueueModel(() -> 0);
        m.start("j", "j");
        m.fail("j", "No channel tiles found");
        Job j = m.job("j");
        assertEquals(State.FAILED, j.state());
        assertEquals("No channel tiles found", j.status());
        assertEquals(1, m.failedCount());
        assertTrue(m.isIdle());
        m.fail("j", "again");
        assertEquals("No channel tiles found", m.job("j").status(), "a finished job is not rewritten");
        m.clearFinished();
        assertTrue(m.jobs().isEmpty());
    }

    @Test
    void startWithoutEnqueueAndUpdateBeforeStartAreTolerated() {
        // StitchingRecoveryWorkflow and bounded acquisition call show() with no enqueue.
        StitchingQueueModel m = new StitchingQueueModel(() -> 0);
        m.start("r", "re-stitch");
        assertEquals(State.RUNNING, m.job("r").state());
        m.enqueue("q", "q");
        m.updateStatus("q", "Initializing");
        assertEquals(State.RUNNING, m.job("q").state(), "a status line means the stitcher reached it");
        m.updateStatus("unknown", "x");
        assertNull(m.job("unknown"));
        // Re-enqueueing a finished id starts a fresh record (a re-stitch of the same region).
        m.complete("r");
        m.enqueue("r", "re-stitch");
        assertEquals(State.QUEUED, m.job("r").state());
        assertTrue(m.job("r").outputs().isEmpty());
    }
}
