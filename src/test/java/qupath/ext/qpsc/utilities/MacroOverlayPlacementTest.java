package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.AffineTransform;
import org.junit.jupiter.api.Test;

/**
 * The Stage Map moves the preset-placed macro onto where the slide's own alignment puts it
 * ({@link MacroOverlayPlacement}), using the slide centre each alignment records
 * ({@link AffineTransformManager#imageCentreOnStage}).
 */
class MacroOverlayPlacementTest {

    private static final double PX = 81.0; // macro um per pixel (Ocus40-like)

    @Test
    void theAnchorPixelIsDrawnAtTheAnchorStagePoint() {
        double[] at = MacroOverlayPlacement.presetStageOf(400, 150, 400, 150, 5000, -2000, PX, 81, 81);
        assertArrayEquals(new double[] {5000, -2000}, at, 1e-9);
    }

    @Test
    void otherPixelsFollowThePresetAxisSigns() {
        // 10 macro px right of and 4 below the anchor.
        double[] normal = MacroOverlayPlacement.presetStageOf(410, 154, 400, 150, 5000, -2000, PX, 81, 81);
        assertArrayEquals(new double[] {5000 + 10 * PX, -2000 + 4 * PX}, normal, 1e-9);
        // A preset whose transform runs the other way on both axes (stage-inverted scope).
        double[] inverted = MacroOverlayPlacement.presetStageOf(410, 154, 400, 150, 5000, -2000, PX, -81, -81);
        assertArrayEquals(new double[] {5000 - 10 * PX, -2000 - 4 * PX}, inverted, 1e-9);
    }

    @Test
    void aSlideWhereThePresetsSlideLayNeedsNoShift() {
        // The preset's own slide: its green-box centre is the anchor, and its alignment puts its
        // image centre exactly at the anchor's stage point.
        double[] presetAt = MacroOverlayPlacement.presetStageOf(400, 150, 400, 150, 5000, -2000, PX, 81, 81);
        double[] shift = MacroOverlayPlacement.shiftOntoSlide(new double[] {5000, -2000}, presetAt);
        assertArrayEquals(new double[] {0, 0}, shift, 1e-9);
    }

    @Test
    void aSlideDroppedElsewhereIsMovedOntoItsMeasuredCentre() {
        // This slide's green box sits 6 px right of the preset's on the macro, so the preset
        // draws its scan centre 486 um right of the anchor; its alignment measured it 1.2 mm
        // right and 0.3 mm up of that.
        double[] presetAt = MacroOverlayPlacement.presetStageOf(406, 150, 400, 150, 5000, -2000, PX, 81, 81);
        double[] measured = {presetAt[0] + 1200, presetAt[1] - 300};
        double[] shift = MacroOverlayPlacement.shiftOntoSlide(measured, presetAt);
        assertArrayEquals(new double[] {1200, -300}, shift, 1e-9);
        // After the shift the green-box centre lands on the measured centre.
        assertEquals(measured[0], presetAt[0] + shift[0], 1e-9);
        assertEquals(measured[1], presetAt[1] + shift[1], 1e-9);
    }

    @Test
    void theRecordedCentreIsTheFullResolutionImageCentre() {
        // 0.25 um/px full-res alignment with stage X inverted, origin at (10000, 2000).
        AffineTransform t = new AffineTransform(-0.25, 0, 0, 0.25, 10000, 2000);
        double[] c = AffineTransformManager.imageCentreOnStage(t, 40000, 20000);
        assertArrayEquals(new double[] {10000 - 0.25 * 20000, 2000 + 0.25 * 10000}, c, 1e-9);
        assertNull(AffineTransformManager.imageCentreOnStage(t, 0, 20000));
        assertNull(AffineTransformManager.imageCentreOnStage(null, 1, 1));
    }

    @Test
    void theCentreIsTheSameWhicheverWayTheEntryIsFlipped() {
        int w = 40000, h = 20000;
        AffineTransform base = new AffineTransform(0.25, 0, 0, 0.25, 1000, 500);
        // The same slide's alignment on its X-flipped sibling: pixel x maps to base pixel w - x.
        AffineTransform flipped = new AffineTransform(base);
        flipped.concatenate(new AffineTransform(-1, 0, 0, 1, w, 0));
        assertArrayEquals(
                AffineTransformManager.imageCentreOnStage(base, w, h),
                AffineTransformManager.imageCentreOnStage(flipped, w, h),
                1e-9);
    }

    @Test
    void aStoredCentreMustBeTwoFiniteNumbers() {
        assertArrayEquals(
                new double[] {1.5, -2.0}, AffineTransformManager.parsePoint(java.util.List.of(1.5, -2.0)), 1e-12);
        assertNull(AffineTransformManager.parsePoint(java.util.List.of(1.0)));
        assertNull(AffineTransformManager.parsePoint(java.util.List.of(Double.NaN, 1.0)));
        assertNull(AffineTransformManager.parsePoint("1,2"));
    }
}
