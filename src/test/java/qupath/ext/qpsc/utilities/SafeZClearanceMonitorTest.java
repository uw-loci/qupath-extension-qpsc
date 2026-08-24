package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SafeZClearanceMonitor} -- the per-run check that the declared retraction is still a
 * retraction.
 *
 * <p>The failure it catches is silent by construction: focus keeps succeeding as the sample
 * plane drifts toward the retraction point, right up to the run where the two cross and
 * "retracting" drives the objective into the sample. Nothing in an autofocus result reveals it,
 * which is why it is checked against the declared value rather than inferred from focus quality.
 *
 * <p>Numbers below are taken from the 2026-08-14 PPM runs: focus positions -421..-185 um against
 * the safe Z of -500 um agreed for that scope.
 */
class SafeZClearanceMonitorTest {

    @AfterEach
    void reset() {
        SafeZClearanceMonitor.cancel();
    }

    @Test
    void healthyClearanceReportsNothing() {
        SafeZClearanceMonitor.begin(-500.0, "quad_v / ppm_20x");
        for (double z : new double[] {-420.9, -399.0, -395.1, -366.2, -322.6, -304.3, -185.0}) {
            SafeZClearanceMonitor.recordFocus(z);
        }
        assertNull(SafeZClearanceMonitor.report(), "80+ um of clearance is fine");
    }

    @Test
    void focusStraddlingTheSafeZIsTheSeriousCase() {
        // One slide focuses beyond the retraction point: retracting would drive into it.
        SafeZClearanceMonitor.begin(-500.0, "quad_v / ppm_20x");
        SafeZClearanceMonitor.recordFocus(-420.0);
        SafeZClearanceMonitor.recordFocus(-380.0);
        SafeZClearanceMonitor.recordFocus(-515.0);

        String msg = SafeZClearanceMonitor.report();

        assertNotNull(msg);
        assertTrue(msg.contains("BOTH sides"), msg);
        assertTrue(msg.contains("Re-measure"), msg);
    }

    @Test
    void shrinkingClearanceIsReportedBeforeItCrosses() {
        SafeZClearanceMonitor.begin(-500.0, "dish35 / bf_10x");
        SafeZClearanceMonitor.recordFocus(-470.0); // 30 um clearance -- the closest
        SafeZClearanceMonitor.recordFocus(-462.0); // 38 um

        String msg = SafeZClearanceMonitor.report();

        assertNotNull(msg, "the point is to warn BEFORE the crossing, not after");
        assertTrue(msg.contains("30.0"), "should report the CLOSEST approach, not the last: " + msg);
        assertTrue(msg.contains("dish35 / bf_10x"), msg);
    }

    @Test
    void clearanceIsDirectionAgnostic() {
        // A rig that retracts in the positive direction: focus sits BELOW the safe Z. The
        // monitor must not assume which sign means retracted.
        SafeZClearanceMonitor.begin(1200.0, "single_h / fluor");
        SafeZClearanceMonitor.recordFocus(900.0);
        SafeZClearanceMonitor.recordFocus(1050.0);
        assertNull(SafeZClearanceMonitor.report(), "consistently-below focus is healthy too");

        SafeZClearanceMonitor.begin(1200.0, "single_h / fluor");
        SafeZClearanceMonitor.recordFocus(1180.0);
        assertNotNull(SafeZClearanceMonitor.report(), "20 um clearance is too tight either way");
    }

    @Test
    void anUndeclaredSafeZDisablesTheMonitor() {
        SafeZClearanceMonitor.begin(null, "quad_v / ppm_20x");
        SafeZClearanceMonitor.recordFocus(-400.0);
        assertNull(SafeZClearanceMonitor.report(), "nothing to check against");
    }

    @Test
    void aRunWithNoFocusPositionsReportsNothing() {
        SafeZClearanceMonitor.begin(-500.0, "quad_v / ppm_20x");
        assertNull(SafeZClearanceMonitor.report());
    }

    @Test
    void recordingOutsideARunIsHarmless() {
        SafeZClearanceMonitor.cancel();
        SafeZClearanceMonitor.recordFocus(-400.0);
        assertNull(SafeZClearanceMonitor.report());
    }

    @Test
    void reportIsOneShotSoItCannotWarnTwiceForOneRun() {
        SafeZClearanceMonitor.begin(-500.0, "quad_v / ppm_20x");
        SafeZClearanceMonitor.recordFocus(-490.0);
        assertNotNull(SafeZClearanceMonitor.report());
        assertNull(SafeZClearanceMonitor.report(), "a second call must not re-warn");
    }

    @Test
    void nanFocusValuesAreIgnored() {
        SafeZClearanceMonitor.begin(-500.0, "quad_v / ppm_20x");
        SafeZClearanceMonitor.recordFocus(Double.NaN);
        SafeZClearanceMonitor.recordFocus(-300.0);
        assertNull(SafeZClearanceMonitor.report());
    }
}
