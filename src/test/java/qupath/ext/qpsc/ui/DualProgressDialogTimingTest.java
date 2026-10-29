package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    @Test
    void laterAnnotationsCountAtTheCurrentFilesPerPosition() {
        // 2026-10-29 LC-PolScope run, two annotations: the second has 80 positions of 5 states.
        // Its size was sent in files at 1 file per position (states not yet known), so during
        // the first annotation the estimate showed it as 16 positions.
        List<Integer> future = Collections.synchronizedList(new ArrayList<>(List.of(80)));
        // First annotation: 50 positions x 5 states, none written yet.
        assertEquals(250 + 400, DualProgressDialog.filesRemaining(250, 0, future, 5));
        // Halfway through it.
        assertEquals(125 + 400, DualProgressDialog.filesRemaining(250, 125, future, 5));
    }

    @Test
    void theLastAnnotationCountsOnlyItsOwnRemainder() {
        List<Integer> none = Collections.synchronizedList(new ArrayList<>());
        assertEquals(30, DualProgressDialog.filesRemaining(400, 370, none, 5));
        assertEquals(0, DualProgressDialog.filesRemaining(400, 410, none, 5));
    }
}
