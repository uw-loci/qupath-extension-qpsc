package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The start-time check warns when one timepoint takes longer than the time-lapse interval. */
class TimeLapseFallsBehindTest {

    @Test
    void theWholeSlideTimeLapseOf2026_11_13FallsBehind() {
        // 1,856 tiles x 3 angles at about 2.75 s per image: about 4.3 h per timepoint, at a
        // 0.1 s interval.
        assertTrue(AcquisitionDialogBuilder.timeLapseFallsBehind(4, 0.1, 1856 * 3 * 2.75));
    }

    @Test
    void anIntervalLongerThanATimepointKeepsUp() {
        assertFalse(AcquisitionDialogBuilder.timeLapseFallsBehind(10, 600, 300));
    }

    @Test
    void aSingleTimepointOrNoEstimateNeverWarns() {
        assertFalse(AcquisitionDialogBuilder.timeLapseFallsBehind(1, 0.1, 1000));
        assertFalse(AcquisitionDialogBuilder.timeLapseFallsBehind(4, 0.1, Double.NaN));
    }
}
