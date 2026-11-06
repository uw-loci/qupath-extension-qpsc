package qupath.ext.qpsc.stitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class JobEtaTest {

    @Test
    void waitingTargetsTakeTheFirstFreeWriter() {
        // 2026-11-06 shape: three angles and the biref on four writers, all started at once.
        assertEquals(100, JobEta.makespan(new double[0], new double[] {100, 100, 100, 60}, 4), 1e-9);
        // The same on two writers: 100+100 on one, 100+60 on the other.
        assertEquals(200, JobEta.makespan(new double[0], new double[] {100, 100, 100, 60}, 2), 1e-9);
        // Running targets hold their writers for their remaining time.
        assertEquals(40 + 100, JobEta.makespan(new double[] {40, 90}, new double[] {100}, 2), 1e-9);
        assertEquals(90, JobEta.makespan(new double[] {40, 90}, new double[0], 2), 1e-9);
    }

    @Test
    void anUnknownInputMakesTheWholeUnknown() {
        assertTrue(Double.isNaN(JobEta.makespan(new double[] {Double.NaN}, new double[0], 2)));
        assertTrue(Double.isNaN(JobEta.makespan(new double[0], new double[] {1, Double.NaN}, 2)));
        assertTrue(Double.isNaN(JobEta.queueSeconds(new double[] {10}, new double[] {Double.NaN})));
    }

    @Test
    void theQueueRunsJobsOneAfterAnother() {
        assertEquals(3600 + 1800 + 1200, JobEta.queueSeconds(new double[] {3600}, new double[] {1800, 1200}), 1e-9);
        assertEquals(0, JobEta.queueSeconds(new double[0], new double[0]), 1e-9);
    }

    @Test
    void theQueueModelSumsRunningAndQueuedJobs() {
        AtomicLong now = new AtomicLong(0);
        StitchingQueueModel model = new StitchingQueueModel(now::get);
        model.enqueue("a", "A");
        model.enqueue("b", "B");
        model.start("a", "A");
        assertTrue(Double.isNaN(model.queueRemainingSec()), "nothing known yet");
        model.updateRemaining("a", 7200);
        model.setPrediction("b", 1800);
        assertEquals(9000, model.queueRemainingSec(), 1e-9);
        assertEquals(7200, model.job("a").timeLeftSec(), 1e-9);
        assertEquals(1800, model.job("b").timeLeftSec(), 1e-9);
        assertTrue(model.summary().contains("all done in about 2 h 30 min"), model.summary());
        model.complete("a");
        assertEquals(0, model.job("a").timeLeftSec(), 1e-9);
        assertEquals(1800, model.queueRemainingSec(), 1e-9);
    }

    @Test
    void durationsReadNaturally() {
        assertEquals("45 s", StitchingQueueModel.formatDuration(45));
        assertEquals("12 min", StitchingQueueModel.formatDuration(12 * 60));
        assertEquals("9 h 33 min", StitchingQueueModel.formatDuration(9 * 3600 + 33 * 60));
    }
}
