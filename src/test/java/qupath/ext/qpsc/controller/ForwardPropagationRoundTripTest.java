package qupath.ext.qpsc.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import org.junit.jupiter.api.Test;

/**
 * Pure-math tests for the propagation transform chain.
 *
 * <p>The full {@code propagateForward} / {@code propagateBack} methods read from
 * QuPath {@code ImageData} and project entries, which require a live project
 * to drive end-to-end. These tests pin down the building blocks instead:
 *
 * <ul>
 *   <li>{@link ForwardPropagationWorkflow#createFlip} — flip transform geometry.</li>
 *   <li>The XOR delta-flip used by {@code propagateBackFanOut} — a point in the
 *       canonical sibling's pixel frame maps to its mirror in any other sibling
 *       whose flip differs by exactly one axis.</li>
 *   <li>{@code subToStage} composed with the inverse of a fabricated
 *       {@code baseToStage} round-trips a known landmark to within 1 pixel.</li>
 * </ul>
 */
class ForwardPropagationRoundTripTest {

    private static final int IMG_W = 2000;
    private static final int IMG_H = 1500;

    @Test
    void createFlip_unflipped_isIdentity() {
        AffineTransform t = ForwardPropagationWorkflow.createFlip(false, false, IMG_W, IMG_H);
        assertEquals(0, Double.compare(t.getScaleX(), 1.0));
        assertEquals(0, Double.compare(t.getScaleY(), 1.0));
        assertEquals(0, Double.compare(t.getTranslateX(), 0.0));
        assertEquals(0, Double.compare(t.getTranslateY(), 0.0));
    }

    @Test
    void createFlip_xOnly_mirrorsPoint() {
        AffineTransform t = ForwardPropagationWorkflow.createFlip(true, false, IMG_W, IMG_H);
        Point2D src = new Point2D.Double(123, 456);
        Point2D dst = t.transform(src, null);
        assertEquals(IMG_W - 123, dst.getX(), 1e-9);
        assertEquals(456, dst.getY(), 1e-9);
    }

    @Test
    void createFlip_yOnly_mirrorsPoint() {
        AffineTransform t = ForwardPropagationWorkflow.createFlip(false, true, IMG_W, IMG_H);
        Point2D dst = t.transform(new Point2D.Double(123, 456), null);
        assertEquals(123, dst.getX(), 1e-9);
        assertEquals(IMG_H - 456, dst.getY(), 1e-9);
    }

    @Test
    void createFlip_xy_mirrorsBoth() {
        AffineTransform t = ForwardPropagationWorkflow.createFlip(true, true, IMG_W, IMG_H);
        Point2D dst = t.transform(new Point2D.Double(123, 456), null);
        assertEquals(IMG_W - 123, dst.getX(), 1e-9);
        assertEquals(IMG_H - 456, dst.getY(), 1e-9);
    }

    @Test
    void createFlip_isInvolution() {
        // Applying the same flip twice returns to the original point.
        for (boolean fx : new boolean[]{false, true}) {
            for (boolean fy : new boolean[]{false, true}) {
                AffineTransform t = ForwardPropagationWorkflow.createFlip(fx, fy, IMG_W, IMG_H);
                Point2D src = new Point2D.Double(537.25, 891.75);
                Point2D once = t.transform(src, null);
                Point2D twice = t.transform(once, null);
                assertEquals(src.getX(), twice.getX(), 1e-9, "fx=" + fx + ", fy=" + fy);
                assertEquals(src.getY(), twice.getY(), 1e-9, "fx=" + fx + ", fy=" + fy);
            }
        }
    }

    /**
     * Delta-flip semantics: given a canonical sibling with flip (cx, cy) and a
     * target sibling with flip (sx, sy), a point in canonical pixel frame should
     * be mapped to the target frame by applying the flip with delta = (cx XOR sx, cy XOR sy).
     */
    @Test
    void deltaFlip_betweenSiblings_isXorOfFlips() {
        // Canonical = unflipped, target = flipped X.
        AffineTransform delta = ForwardPropagationWorkflow.createFlip(true, false, IMG_W, IMG_H);
        Point2D pCanonical = new Point2D.Double(700, 500);
        Point2D pTarget = delta.transform(pCanonical, null);
        assertEquals(IMG_W - 700, pTarget.getX(), 1e-9);
        assertEquals(500, pTarget.getY(), 1e-9);

        // Canonical = flipped X, target = flipped XY: delta is Y only.
        AffineTransform deltaXY = ForwardPropagationWorkflow.createFlip(false, true, IMG_W, IMG_H);
        Point2D pCanonical2 = new Point2D.Double(IMG_W - 700, 500); // same point in flipped X frame
        Point2D pTarget2 = deltaXY.transform(pCanonical2, null);
        assertEquals(IMG_W - 700, pTarget2.getX(), 1e-9);
        assertEquals(IMG_H - 500, pTarget2.getY(), 1e-9);
        // Both targets describe the same physical landmark in their respective frames.
    }

    /**
     * Round-trip a known landmark through a fabricated baseToStage transform.
     * Forward: base_pixels -> stage_microns -> sub_pixels.
     * Back:    sub_pixels  -> stage_microns -> base_pixels.
     * Composition should recover the original within sub-pixel tolerance.
     */
    @Test
    void roundTrip_landmark_recoversInputWithinOnePixel() throws Exception {
        // Fabricate a baseToStage: scale 0.5 um/pixel, translate to (1234, 5678) um origin.
        AffineTransform baseToStage = new AffineTransform();
        baseToStage.translate(1234.0, 5678.0);
        baseToStage.scale(0.5, 0.5);

        // Sub-image parameters (matches what propagateForward / propagateBack consume).
        double subPixelSize = 0.25; // um/pixel (4x sampling)
        double[] xyOffset = {1500.0, 5800.0}; // top-left of sub in stage microns
        double halfFovX = 50.0;
        double halfFovY = 40.0;
        double correctedX = xyOffset[0] - halfFovX;
        double correctedY = xyOffset[1] - halfFovY;

        // Forward chain: stageToSub * baseToStage
        AffineTransform stageToSub = new AffineTransform();
        stageToSub.scale(1.0 / subPixelSize, 1.0 / subPixelSize);
        stageToSub.translate(-correctedX, -correctedY);
        AffineTransform forward = new AffineTransform(stageToSub);
        forward.concatenate(baseToStage);

        // Back chain: stageToBase * subToStage
        AffineTransform subToStage = new AffineTransform();
        subToStage.translate(correctedX, correctedY);
        subToStage.scale(subPixelSize, subPixelSize);
        AffineTransform stageToBase = baseToStage.createInverse();
        AffineTransform back = new AffineTransform(stageToBase);
        back.concatenate(subToStage);

        // Pick a base-pixel landmark, push through forward, then back.
        Point2D base = new Point2D.Double(800.0, 600.0);
        Point2D sub = forward.transform(base, null);
        Point2D recovered = back.transform(sub, null);
        assertEquals(base.getX(), recovered.getX(), 1.0,
                "base X recovered within 1 pixel");
        assertEquals(base.getY(), recovered.getY(), 1.0,
                "base Y recovered within 1 pixel");
        // For a clean (no-flip, no-shear) transform we should be effectively exact.
        assertEquals(base.getX(), recovered.getX(), 1e-6);
        assertEquals(base.getY(), recovered.getY(), 1e-6);
    }
}
