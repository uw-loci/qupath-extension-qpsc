package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.TransformationFunctions.SimilarityFit;

/**
 * Unit tests for {@link TransformationFunctions#computeStageSpaceSimilarity}, the
 * multi-tile refinement solver. These are pure math (no JavaFX / hardware) and pin
 * down the rotation sign, scale recovery, translation, and -- critically -- that
 * composing the correction onto an initial transform reproduces the intended
 * pixel->stage map.
 */
public class StageSpaceSimilarityTest {

    private static final double EPS = 1e-6;

    /** A pure translation between predicted and measured -> zero rotation, unit scale. */
    @Test
    public void pureTranslationRecovered() {
        double[][] predicted = {{0, 0}, {100, 0}, {0, 100}};
        double[][] measured = {{10, -5}, {110, -5}, {10, 95}};
        SimilarityFit fit = TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);

        assertEquals(0.0, fit.rotationDegrees(), 1e-4, "no rotation");
        assertEquals(1.0, fit.scale(), 1e-6, "unit scale");
        assertEquals(0.0, fit.rmsResidualUm(), 1e-6, "exact fit");

        Point2D out = fit.correction().transform(new Point2D.Double(50, 50), null);
        assertEquals(60.0, out.getX(), EPS);
        assertEquals(45.0, out.getY(), EPS);
    }

    /**
     * A known 30-degree rotation about the origin (CCW in stage coords) plus a
     * translation must be recovered in both angle and the mapped points.
     */
    @Test
    public void rotationRecovered() {
        double theta = Math.toRadians(30);
        double c = Math.cos(theta), s = Math.sin(theta);
        double tx = 200, ty = -50;
        double[][] predicted = {{0, 0}, {100, 0}, {40, 80}, {-60, 20}};
        double[][] measured = new double[predicted.length][2];
        for (int i = 0; i < predicted.length; i++) {
            double x = predicted[i][0], y = predicted[i][1];
            measured[i][0] = c * x - s * y + tx;
            measured[i][1] = s * x + c * y + ty;
        }

        SimilarityFit fit = TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);
        assertEquals(30.0, fit.rotationDegrees(), 1e-4, "recovers +30 deg");
        assertEquals(1.0, fit.scale(), 1e-6, "no scale change");
        assertTrue(fit.rmsResidualUm() < 1e-6, "exact fit for noise-free rotation");

        for (int i = 0; i < predicted.length; i++) {
            Point2D out = fit.correction().transform(new Point2D.Double(predicted[i][0], predicted[i][1]), null);
            assertEquals(measured[i][0], out.getX(), 1e-4);
            assertEquals(measured[i][1], out.getY(), 1e-4);
        }
    }

    /** A uniform 1.05x scale (residual pixel-size error) must be recovered. */
    @Test
    public void scaleRecovered() {
        double scale = 1.05;
        double[][] predicted = {{0, 0}, {200, 0}, {0, 200}};
        double[][] measured = new double[predicted.length][2];
        for (int i = 0; i < predicted.length; i++) {
            measured[i][0] = scale * predicted[i][0] + 5;
            measured[i][1] = scale * predicted[i][1] - 5;
        }
        SimilarityFit fit = TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);
        assertEquals(0.0, fit.rotationDegrees(), 1e-4);
        assertEquals(1.05, fit.scale(), 1e-6, "recovers 1.05x scale");
    }

    /**
     * The end-to-end use: an initial pixel->stage transform is off by a small slot
     * rotation. Composing the recovered correction onto it must reproduce the true
     * pixel->stage map at test pixels away from the measured ones.
     */
    @Test
    public void compositionReproducesTrueMap() {
        // Initial (wrong) alignment: 0.25 um/px, no rotation.
        AffineTransform initial = new AffineTransform(0.25, 0, 0, 0.25, 1000, -2000);
        // True alignment: same scale/translation but the slide sits rotated 2 deg.
        double theta = Math.toRadians(2);
        double c = Math.cos(theta), s = Math.sin(theta);
        AffineTransform trueMap = new AffineTransform(0.25 * c, 0.25 * s, -0.25 * s, 0.25 * c, 1000, -2000);

        // Reference tiles (pixels), predicted via initial, measured via the true map.
        double[][] pixels = {{0, 0}, {4000, 0}, {2000, 5000}};
        double[][] predicted = new double[pixels.length][2];
        double[][] measured = new double[pixels.length][2];
        for (int i = 0; i < pixels.length; i++) {
            Point2D p = initial.transform(new Point2D.Double(pixels[i][0], pixels[i][1]), null);
            Point2D m = trueMap.transform(new Point2D.Double(pixels[i][0], pixels[i][1]), null);
            predicted[i] = new double[] {p.getX(), p.getY()};
            measured[i] = new double[] {m.getX(), m.getY()};
        }

        SimilarityFit fit = TransformationFunctions.computeStageSpaceSimilarity(predicted, measured);
        AffineTransform refined = new AffineTransform(fit.correction());
        refined.concatenate(initial);

        // A pixel far from every reference tile must now map like the true alignment
        // (single-tile translation-only would fail here -- that is the whole point).
        Point2D testPixel = new Point2D.Double(3500, 4200);
        Point2D refinedStage = refined.transform(testPixel, null);
        Point2D trueStage = trueMap.transform(testPixel, null);
        assertEquals(trueStage.getX(), refinedStage.getX(), 1e-3, "refined X matches true map");
        assertEquals(trueStage.getY(), refinedStage.getY(), 1e-3, "refined Y matches true map");
    }

    /** Fewer than two points cannot solve -> identity correction, flagged by pointCount. */
    @Test
    public void degenerateReturnsIdentity() {
        SimilarityFit fit =
                TransformationFunctions.computeStageSpaceSimilarity(new double[][] {{0, 0}}, new double[][] {{5, 5}});
        assertTrue(fit.correction().isIdentity(), "single point -> identity");
        assertEquals(1, fit.pointCount());
    }
}
