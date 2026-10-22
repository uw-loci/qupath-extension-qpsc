package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

/** The time estimate averages time per file, however many files one progress poll covers. */
class DualProgressDialogTimingTest {

    @Test
    void aPollCoveringSeveralFilesCountsAsThatManyFiles() {
        // 10-22 LC-PolScope run: polls of 1 file at 1.8 s and of 5 files at 9 s. Averaged per
        // poll that is 5.4 s, which times 5370 files gave "8 hr" for a run with 2.7 hr left.
        ArrayDeque<Long> samples = new ArrayDeque<>();
        DualProgressDialog.addFileTimes(samples, 1800, 1, 1000);
        DualProgressDialog.addFileTimes(samples, 9000, 5, 1000);
        assertEquals(6, samples.size());
        assertEquals(1800.0, DualProgressDialog.meanFileTimeMs(samples), 1e-9);
    }

    @Test
    void oldestSamplesGoPastTheCap() {
        ArrayDeque<Long> samples = new ArrayDeque<>();
        DualProgressDialog.addFileTimes(samples, 100, 1, 3);
        DualProgressDialog.addFileTimes(samples, 600, 3, 3);
        assertEquals(3, samples.size());
        assertEquals(200.0, DualProgressDialog.meanFileTimeMs(samples), 1e-9);
    }

    @Test
    void noFilesAddsNothing() {
        ArrayDeque<Long> samples = new ArrayDeque<>();
        DualProgressDialog.addFileTimes(samples, 500, 0, 10);
        assertEquals(-1.0, DualProgressDialog.meanFileTimeMs(samples), 1e-9);
    }
}
