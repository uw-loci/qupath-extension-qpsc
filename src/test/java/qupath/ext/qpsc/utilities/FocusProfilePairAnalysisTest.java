package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The two-position validation: one scan over tissue, one over bare slide at the same Z range.
 *
 * <p>This is what turns "is that peak a coverslip?" from a heuristic into a measurement. A peak
 * present in BOTH scans cannot be tissue -- there was no tissue in the second one -- so it is a
 * surface. A peak present only over tissue is the sample plane. Nothing about the threshold
 * decides this; the background scan does.
 *
 * <p>Profiles are synthesised at 0.3 um spacing, matching a 38 Hz camera against an 11.5 um/s
 * scan.
 */
class FocusProfilePairAnalysisTest {

    private static final double STEP = 0.3;

    private static double bump(double z, double centre, double sigma, double amplitude) {
        double d = z - centre;
        return amplitude * Math.exp(-(d * d) / (2 * sigma * sigma));
    }

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
    void aCleanRigNeedsNoTissueGate() {
        // Tissue scan: one peak. Background scan: nothing but noise-free baseline.
        double[][] tissue = profile(-500, -250, z -> 5 + bump(z, -300, 4, 100));
        double[][] bg = profile(-500, -250, z -> 5.0);

        var v = FocusProfileAnalysis.analysePair(tissue[0], tissue[1], bg[0], bg[1], -300);

        assertTrue(v.usable(), "reasons: " + v.reasons());
        assertFalse(v.requiresTissueGate(), "no surface peaks means first-peak commitment is safe");
        assertTrue(v.tissuePeakIsDistinct());
        assertTrue(v.surfacePeaks().isEmpty());
    }

    @Test
    void aCoverslipPeakInBothScansIsIdentifiedAsASurface() {
        // The peak at -380 appears with and without tissue, so it is glass. The peak at -300
        // only appears over tissue, so it is the sample plane.
        double[][] tissue = profile(-500, -250, z -> 5 + bump(z, -380, 3, 55) + bump(z, -300, 4, 100));
        double[][] bg = profile(-500, -250, z -> 5 + bump(z, -380, 3, 55));

        var v = FocusProfileAnalysis.analysePair(tissue[0], tissue[1], bg[0], bg[1], -300);

        assertTrue(v.tissuePeakIsDistinct(), "the -300 peak has no background counterpart");
        assertTrue(v.requiresTissueGate(), "a surface peak sits before focus");
        assertEquals(1, v.surfacePeaks().size());
        assertEquals(-380, v.surfacePeaks().get(0).z(), 2.0);
        assertTrue(
                v.reasons().stream().anyMatch(r -> r.contains("confirmed by the background scan")),
                "the finding should credit the background scan: " + v.reasons());
    }

    @Test
    void autofocusKeyingOnGlassRatherThanTissueIsCaught() {
        // The strongest peak over tissue is ALSO present without tissue: whatever this
        // modality/objective is focusing on, it is not the sample.
        double[][] tissue = profile(-500, -250, z -> 5 + bump(z, -380, 4, 100) + bump(z, -300, 4, 20));
        double[][] bg = profile(-500, -250, z -> 5 + bump(z, -380, 4, 100));

        var v = FocusProfileAnalysis.analysePair(tissue[0], tissue[1], bg[0], bg[1], -300);

        assertFalse(v.usable(), "must not license an approach that focuses on glass");
        assertFalse(v.tissuePeakIsDistinct());
        assertTrue(
                v.reasons().stream().anyMatch(r -> r.contains("also appears with no tissue present")),
                v.reasons().toString());
    }

    @Test
    void aBackgroundPeakAfterFocusDoesNotForceATissueGate() {
        // A surface BEYOND focus is never reached by an approach that stops at focus, so it is
        // informative but not a reason to require the gate.
        double[][] tissue = profile(-500, -250, z -> 5 + bump(z, -300, 4, 100) + bump(z, -270, 3, 50));
        double[][] bg = profile(-500, -250, z -> 5 + bump(z, -270, 3, 50));

        var v = FocusProfileAnalysis.analysePair(tissue[0], tissue[1], bg[0], bg[1], -300);

        assertTrue(v.usable(), "reasons: " + v.reasons());
        assertFalse(v.requiresTissueGate(), "a surface past focus is never encountered");
    }

    @Test
    void aBackgroundScanWithNoTissuePeakIsTheNormalCase() {
        // Sanity: bare slide should not produce a focus peak of its own to confuse matters.
        double[][] bg = profile(-500, -250, z -> 5 + 0.01 * (z + 500));
        assertTrue(
                FocusProfileAnalysis.prominentPeaks(bg[0], bg[1]).isEmpty(), "a monotonic ramp has no prominent peak");
    }

    @Test
    void anUnusableTissueScanFailsWithoutBlamingTheBackground() {
        double[][] tissue = profile(-500, -250, z -> 42.0);
        double[][] bg = profile(-500, -250, z -> 42.0);

        var v = FocusProfileAnalysis.analysePair(tissue[0], tissue[1], bg[0], bg[1], -300);

        assertFalse(v.usable());
        assertTrue(
                v.reasons().stream().anyMatch(r -> r.contains("flat")),
                v.reasons().toString());
    }

    @Test
    void degenerateInputIsRejectedNotCrashed() {
        double[][] ok = profile(-500, -250, z -> 5 + bump(z, -300, 4, 100));
        var v = FocusProfileAnalysis.analysePair(null, null, ok[0], ok[1], -300);
        assertFalse(v.usable());
        assertTrue(FocusProfileAnalysis.prominentPeaks(null, null).isEmpty());
    }
}
