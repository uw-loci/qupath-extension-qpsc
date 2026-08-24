package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * {@link FocusProfileAnalysis} -- the check that decides whether approach-from-safe-Z
 * autofocus can be trusted on a given system and modality.
 *
 * <p>The decision it protects is physical: an approach that commits to the first prominent
 * peak will focus on a coverslip if one produces a peak before the tissue, and at high
 * magnification the coverslip is the first thing the objective meets. So the cases below are
 * built as the profiles a real rig produces, not as abstract signal shapes.
 *
 * <p>Profiles are synthesised on a 0.3 um sample spacing, matching a 38 Hz camera against an
 * 11.5 um/s scan.
 */
class FocusProfileAnalysisTest {

    private static final double STEP = 0.3;

    /** Gaussian bump helper. */
    private static double bump(double z, double centre, double sigma, double amplitude) {
        double d = z - centre;
        return amplitude * Math.exp(-(d * d) / (2 * sigma * sigma));
    }

    /** Builds a profile over [zStart, zEnd] from a sampling function. */
    private static double[][] profile(double zStart, double zEnd, java.util.function.DoubleUnaryOperator f) {
        int n = (int) Math.round(Math.abs(zEnd - zStart) / STEP) + 1;
        double dir = Math.signum(zEnd - zStart);
        double[] z = new double[n];
        double[] m = new double[n];
        for (int i = 0; i < n; i++) {
            z[i] = zStart + dir * i * STEP;
            m[i] = f.applyAsDouble(z[i]);
        }
        return new double[][] {z, m};
    }

    @Test
    void aCleanSinglePeakApproachIsUsable() {
        // Retract at -500, tissue focus at -300: metric rises to a single peak.
        double[][] p = profile(-500, -250, z -> 5 + bump(z, -300, 4, 100));

        var v = FocusProfileAnalysis.analyse(p[0], p[1], -300);

        assertTrue(v.usable(), "clean profile should pass; reasons: " + v.reasons());
        assertEquals(-300, v.globalMaxZ(), 1.0);
        assertEquals(0.0, v.focusOffsetUm(), 1.0);
        assertTrue(v.falsePeaks().isEmpty());
        assertTrue(v.risingFraction() > 0.95);
        assertEquals(200, v.approachDistanceUm(), 1.0);
    }

    @Test
    void aCoverslipPeakBeforeFocusIsRejected() {
        // The case the whole check exists for: a real contrast peak from a glass surface at
        // -380, then tissue at -300. Stopping at the first peak focuses on glass.
        double[][] p = profile(-500, -250, z -> 5 + bump(z, -380, 3, 55) + bump(z, -300, 4, 100));

        var v = FocusProfileAnalysis.analyse(p[0], p[1], -300);

        assertFalse(v.usable(), "a prominent pre-focus peak must fail the check");
        assertFalse(v.falsePeaks().isEmpty());
        assertEquals(-380, v.falsePeaks().get(0).z(), 2.0);
        assertTrue(
                v.reasons().stream().anyMatch(r -> r.contains("coverslip") || r.contains("before focus")),
                "the reason should name the pre-focus peak: " + v.reasons());
    }

    @Test
    void aShoulderOnTheWayUpIsNotTreatedAsAFalsePeak() {
        // A broad low rise merging into the main peak: the metric dips only ~2% between them,
        // so an approach would sail straight through. Flagging that would train operators to
        // ignore the check. (Contrast with the coverslip case above, which dips ~50%.)
        double[][] p = profile(-500, -250, z -> 5 + bump(z, -320, 15, 30) + bump(z, -300, 4, 100));

        var v = FocusProfileAnalysis.analyse(p[0], p[1], -300);

        assertTrue(v.falsePeaks().isEmpty(), "a shallow shoulder is not a competing focus plane");
        assertTrue(v.usable(), "reasons: " + v.reasons());
    }

    @Test
    void aFlatMetricIsRejectedRatherThanPickingNoise() {
        double[][] p = profile(-500, -250, z -> 42.0);

        var v = FocusProfileAnalysis.analyse(p[0], p[1], -300);

        assertFalse(v.usable());
        assertTrue(
                v.reasons().stream().anyMatch(r -> r.contains("flat")),
                "should say the metric is flat: " + v.reasons());
    }

    @Test
    void aPeakAwayFromTheOperatorsFocusIsRejected() {
        // Metric peaks 30 um from where the operator focused: it is peaking on something
        // other than the sample plane, so the approach would commit to the wrong Z.
        double[][] p = profile(-500, -250, z -> 5 + bump(z, -330, 4, 100));

        var v = FocusProfileAnalysis.analyse(p[0], p[1], -300);

        assertFalse(v.usable());
        assertEquals(-30.0, v.focusOffsetUm(), 2.0);
        assertTrue(
                v.reasons().stream().anyMatch(r -> r.contains("by hand")),
                "should compare against the manual focus: " + v.reasons());
    }

    @Test
    void aDescendingApproachIsHandledToo() {
        // Retraction is not always in the negative direction; the rig declares which way.
        double[][] p = profile(-100, -350, z -> 5 + bump(z, -300, 4, 100));

        var v = FocusProfileAnalysis.analyse(p[0], p[1], -300);

        assertTrue(v.usable(), "reasons: " + v.reasons());
        assertEquals(-300, v.globalMaxZ(), 1.0);
        assertEquals(200, v.approachDistanceUm(), 1.0);
    }

    @Test
    void peakWidthIsMeasuredWhenThePeakIsFullyCaptured() {
        // FWHM of a Gaussian is 2*sqrt(2*ln2)*sigma ~ 2.355 sigma.
        double[][] p = profile(-500, -250, z -> 5 + bump(z, -300, 4, 100));

        var v = FocusProfileAnalysis.analyse(p[0], p[1], -300);

        assertEquals(2.355 * 4, v.peakWidthUm(), 2.0);
    }

    @Test
    void aTruncatedPeakReportsNoWidthRatherThanAWrongOne() {
        // Scan stops at the peak, so the far side was never measured.
        double[][] p = profile(-500, -300, z -> 5 + bump(z, -300, 4, 100));

        var v = FocusProfileAnalysis.analyse(p[0], p[1], -300);

        assertTrue(Double.isNaN(v.peakWidthUm()), "an unbracketed peak has no measurable width");
    }

    @Test
    void manualFocusMayBeOmitted() {
        double[][] p = profile(-500, -250, z -> 5 + bump(z, -300, 4, 100));

        var v = FocusProfileAnalysis.analyse(p[0], p[1], Double.NaN);

        assertTrue(Double.isNaN(v.focusOffsetUm()));
        assertTrue(v.usable(), "reasons: " + v.reasons());
    }

    @Test
    void degenerateInputIsRejectedNotCrashed() {
        assertFalse(FocusProfileAnalysis.analyse(null, null, 0).usable());
        assertFalse(FocusProfileAnalysis.analyse(new double[] {1, 2}, new double[] {1, 2}, 0)
                .usable());
        assertFalse(FocusProfileAnalysis.analyse(new double[] {1, 2, 3}, new double[] {1, 2}, 0)
                .usable());
    }
}
