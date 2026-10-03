package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The per-slide focus surface, and the one thing it must be honest about.
 *
 * <p>Three points determine a plane exactly. A fit to three points has a zero residual
 * whatever those points were, so it cannot report its own failure -- the same tautology
 * as a two-correspondence similarity transform, whose RMS is identically zero. Multi-tile
 * refinement measures two or three points, so this is the normal case here and not an
 * edge case, and the record has to carry the distinction forward: such a surface is a
 * reasonable thing to approach a slide with and not a reasonable thing to overrule a
 * measurement with.
 */
class SlideFocusSurfaceTest {

    /** The 2026-09-24 region at stage (35981, -7908): 2.86 / 1.02 um/mm about z0 -304.4. */
    private static final double TILT_X = 2.86;

    private static final double TILT_Y = 1.02;
    private static final double Z0 = -304.4;
    private static final double CX = 35981;
    private static final double CY = -7908;

    private static double plane(double x, double y) {
        return Z0 + TILT_X * (x - CX) / 1000.0 + TILT_Y * (y - CY) / 1000.0;
    }

    private static List<double[]> pointsOn(double[][] xy) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : xy) {
            out.add(new double[] {p[0], p[1], plane(p[0], p[1])});
        }
        return out;
    }

    @Test
    void itRecoversTheTiltAndHeightItWasGiven() {
        var surface = AffineTransformManager.fitFocusSurface(
                pointsOn(new double[][] {
                    {CX - 5000, CY - 4000}, {CX + 5000, CY - 4000},
                    {CX - 5000, CY + 4000}, {CX + 5000, CY + 4000},
                    {CX, CY}
                }),
                "20x");
        assertThat(surface).isNotNull();
        assertThat(surface.tiltXUmPerMm()).isCloseTo(TILT_X, org.assertj.core.data.Offset.offset(0.01));
        assertThat(surface.tiltYUmPerMm()).isCloseTo(TILT_Y, org.assertj.core.data.Offset.offset(0.01));
        assertThat(surface.predict(CX, CY)).isCloseTo(Z0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(surface.tiltUmPerMm())
                .isCloseTo(Math.hypot(TILT_X, TILT_Y), org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void aThreePointFitReportsNoResidualRatherThanZero() {
        // The distinction the whole record exists to carry. Reporting 0.00 um would read
        // as a perfect surface; NaN reads as "this fit cannot be checked", which is true.
        var surface = AffineTransformManager.fitFocusSurface(
                pointsOn(new double[][] {{CX - 5000, CY - 4000}, {CX + 5000, CY - 4000}, {CX, CY + 4000}}), "20x");
        assertThat(surface).isNotNull();
        assertThat(surface.pointCount()).isEqualTo(3);
        assertThat(surface.rmsUm()).isNaN();
        assertThat(surface.hasRedundancy()).isFalse();
    }

    @Test
    void aFourthPointMakesTheFitCheckable() {
        var surface = AffineTransformManager.fitFocusSurface(
                pointsOn(new double[][] {
                    {CX - 5000, CY - 4000}, {CX + 5000, CY - 4000}, {CX, CY + 4000}, {CX + 2000, CY + 1000}
                }),
                "20x");
        assertThat(surface.hasRedundancy()).isTrue();
        assertThat(surface.rmsUm()).isNotNaN().isLessThan(0.01);
    }

    @Test
    void aBadPointAmongFourShowsUpInTheResidual() {
        // With redundancy, a wrong measurement is visible. This is the property that
        // makes a fourth refinement point worth asking for.
        List<double[]> points = pointsOn(
                new double[][] {{CX - 5000, CY - 4000}, {CX + 5000, CY - 4000}, {CX, CY + 4000}, {CX + 2000, CY + 1000}
                });
        points.get(3)[2] += 40.0;
        var surface = AffineTransformManager.fitFocusSurface(points, "20x");
        assertThat(surface.rmsUm()).isGreaterThan(5.0);
    }

    @Test
    void collinearPointsAreRefusedRatherThanFittedAlongOneAxis() {
        // Three points down one line fix the tilt along it and say nothing across it.
        // A surface built from them would extrapolate a tilt nobody measured.
        var surface = AffineTransformManager.fitFocusSurface(
                pointsOn(new double[][] {{CX, CY - 4000}, {CX, CY}, {CX, CY + 4000}}), "20x");
        assertThat(surface).isNull();
    }

    @Test
    void predictionIsLinearInBothAxesAtTheMeasuredTilt() {
        var surface = AffineTransformManager.fitFocusSurface(
                pointsOn(new double[][] {
                    {CX - 5000, CY - 4000}, {CX + 5000, CY - 4000}, {CX, CY + 4000}, {CX + 2000, CY + 1000}
                }),
                "20x");
        // One mm of stage X must move the prediction by exactly the X tilt.
        double atOrigin = surface.predict(CX, CY);
        assertThat(surface.predict(CX + 1000, CY) - atOrigin)
                .isCloseTo(TILT_X, org.assertj.core.data.Offset.offset(0.01));
        assertThat(surface.predict(CX, CY + 1000) - atOrigin)
                .isCloseTo(TILT_Y, org.assertj.core.data.Offset.offset(0.01));
    }
}
