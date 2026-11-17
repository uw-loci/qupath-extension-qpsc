package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.AffineTransform;
import org.junit.jupiter.api.Test;

/** One click at a known stage position corrects the translation and keeps the preset's linear part. */
class ClickPositionRefinementTest {

    private static double[] map(AffineTransform t, double x, double y) {
        double[] out = new double[2];
        t.transform(new double[] {x, y}, 0, out, 0, 1);
        return out;
    }

    @Test
    void theClickedPixelLandsOnTheStageAndTheLinearPartIsKept() {
        // A Camera View companion on a rotated holder: 270 degrees plus a flip, 0.25 um/px.
        AffineTransform initial = new AffineTransform(0.0, -0.25, -0.25, 0.0, 12000.0, 8000.0);
        double[] pixel = {4000, 3000};
        double[] stage = {9100.0, 6900.0};

        AffineTransform refined = ClickPositionRefinement.refine(initial, pixel, stage);

        assertArrayEquals(stage, map(refined, pixel[0], pixel[1]), 1e-9);
        assertEquals(initial.getScaleX(), refined.getScaleX(), 1e-12);
        assertEquals(initial.getShearX(), refined.getShearX(), 1e-12);
        assertEquals(initial.getShearY(), refined.getShearY(), 1e-12);
        assertEquals(initial.getScaleY(), refined.getScaleY(), 1e-12);
        // Every other pixel moves by the same offset.
        double[] a0 = map(initial, 0, 0);
        double[] a1 = map(refined, 0, 0);
        double[] b0 = map(initial, pixel[0], pixel[1]);
        assertEquals(stage[0] - b0[0], a1[0] - a0[0], 1e-9);
        assertEquals(stage[1] - b0[1], a1[1] - a0[1], 1e-9);
    }

    @Test
    void aStageMapPointIsTheAlignmentsPixelForIt() {
        AffineTransform t = new AffineTransform(-0.25, 0, 0, 0.25, 30000, -5000);
        double[] pixel = ClickPositionRefinement.pixelForStagePoint(t, new double[] {29000, -4000});
        assertArrayEquals(new double[] {4000, 4000}, pixel, 1e-9);
        assertNull(
                ClickPositionRefinement.pixelForStagePoint(new AffineTransform(0, 0, 0, 0, 0, 0), new double[] {1, 1}));
    }
}
