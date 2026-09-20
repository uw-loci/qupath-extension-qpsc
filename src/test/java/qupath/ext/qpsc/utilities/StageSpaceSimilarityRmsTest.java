package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the property that makes a 2-point multi-tile refinement unverifiable, using the
 * measurements from the 4-slide PPM run of 2026-09-17.
 *
 * <p>A 2D similarity transform has four degrees of freedom (rotation, isotropic scale, and two
 * translations) and each correspondence supplies two equations. Two correspondences therefore
 * determine the fit exactly: it reproduces both points, and its RMS residual is zero for ANY
 * pair of points, however badly one was measured. On that run, three slides fitted at
 * 0.111-0.255 deg and the fourth at 1.418 deg -- and all four logged RMS 0.00 um, so nothing
 * distinguished them.
 */
class StageSpaceSimilarityRmsTest {

    /** Slide 2's two captured points, in stage micrometers (predicted -> measured). */
    private static final double[][] SLIDE2_PREDICTED = {
        {2559.2838800000004, -30426.273512}, {5347.635672, -22113.813336}
    };

    private static final double[][] SLIDE2_MEASURED = {{2953.0, -29507.0}, {5150.0, -22104.0}};

    @Test
    void twoPointFit_alwaysReportsZeroResidual_evenForTheSlideThatWentWrong() {
        TransformationFunctions.SimilarityFit fit =
                TransformationFunctions.computeStageSpaceSimilarity(SLIDE2_PREDICTED, SLIDE2_MEASURED);

        assertThat(fit.pointCount()).isEqualTo(2);
        // The residual is zero by construction, NOT because the fit is good. Compared against a
        // nanometre rather than exactly 0.0: at stage coordinates of this magnitude the solve
        // leaves floating-point dust, which is still many orders of magnitude below anything
        // that could be called a measurement.
        assertThat(fit.rmsResidualUm()).isLessThan(1e-6);
        // These two points disagree by enough to produce a rotation an order of magnitude larger
        // than the other three slides on the same carrier (which fitted at 0.111-0.255 deg), and
        // the fit still reports a perfect residual.
        //
        // The exact 1.418 deg from the run is NOT reproducible from these numbers: the log's
        // "predicted stage" for point 2 was re-estimated after point 1 was captured, whereas the
        // solver uses the ORIGINAL transform's prediction for both. So assert the property that
        // matters -- a correction far above slide play, with nothing to flag it.
        assertThat(Math.abs(fit.rotationDegrees())).isGreaterThan(0.5);
    }

    @Test
    void twoPointFit_reportsZeroResidualForDeliberatelyInconsistentPoints() {
        // A second point displaced by a full millimetre from where a pure translation would put
        // it. Still RMS 0: there is no third point for it to be inconsistent WITH.
        double[][] predicted = {{0.0, 0.0}, {10000.0, 0.0}};
        double[][] measured = {{100.0, 100.0}, {10100.0, 1100.0}};

        TransformationFunctions.SimilarityFit fit =
                TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);

        assertThat(fit.rmsResidualUm()).isEqualTo(0.0);
        assertThat(Math.abs(fit.rotationDegrees())).isGreaterThan(5.0);
    }

    @Test
    void threePointFit_residualBecomesMeaningful() {
        // The same inconsistency, now witnessed by a third point that agrees with the first two
        // about translation. The fit can no longer absorb the bad point into rotation/scale, so
        // it finally shows up as residual.
        double[][] predicted = {{0.0, 0.0}, {10000.0, 0.0}, {0.0, 10000.0}};
        double[][] measured = {{100.0, 100.0}, {10100.0, 1100.0}, {100.0, 10100.0}};

        TransformationFunctions.SimilarityFit fit =
                TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);

        assertThat(fit.pointCount()).isEqualTo(3);
        assertThat(fit.rmsResidualUm()).isGreaterThan(100.0);
    }

    @Test
    void threePointFit_cleanPointsStillReportLowResidual() {
        // Guards against the previous test passing for the wrong reason: three consistent points
        // (pure 50 um translation) must still fit cleanly.
        double[][] predicted = {{0.0, 0.0}, {10000.0, 0.0}, {0.0, 10000.0}};
        double[][] measured = {{50.0, 50.0}, {10050.0, 50.0}, {50.0, 10050.0}};

        TransformationFunctions.SimilarityFit fit =
                TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);

        assertThat(fit.rmsResidualUm()).isLessThan(1.0);
        assertThat(fit.rotationDegrees()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(fit.scale()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }
}
