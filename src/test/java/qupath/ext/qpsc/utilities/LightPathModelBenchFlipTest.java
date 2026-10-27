package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LightPathModel#benchFlipFlags(String, String)} and
 * {@link LightPathModel#opticalFlipFlags(String)} -- the deterministic reductions that drive the
 * Stage Map's Stage View (bench) and Camera View (optical) and the setup wizard, so the two can
 * never silently diverge. Two binary placement factors span all four axis-aligned orientations
 * (there is no separate turn-over axis -- it is redundant with insertion):
 *
 * <pre>
 *   bench = scopeFace o insertion   (inverted = mirror Y; insertion B = 180)
 *   upright+A id   upright+B (T,T)   inverted+A (F,T)   inverted+B (T,F)
 * </pre>
 */
class LightPathModelBenchFlipTest {

    private static void assertFlip(boolean[] got, boolean x, boolean y, String label) {
        assertEquals(x, got[0], label + " flipX");
        assertEquals(y, got[1], label + " flipY");
    }

    @Test
    void uprightWayAisIdentity() {
        // The inert default: Stage View == Camera View.
        assertFlip(
                LightPathModel.benchFlipFlags(LightPathModel.SCOPE_UPRIGHT, LightPathModel.INSERT_A),
                false,
                false,
                "upright+A");
    }

    @Test
    void uprightWayBis180() {
        assertFlip(
                LightPathModel.benchFlipFlags(LightPathModel.SCOPE_UPRIGHT, LightPathModel.INSERT_B),
                true,
                true,
                "upright+B");
    }

    @Test
    void invertedWayAmirrorsY() {
        assertFlip(
                LightPathModel.benchFlipFlags(LightPathModel.SCOPE_INVERTED, LightPathModel.INSERT_A),
                false,
                true,
                "inverted+A");
    }

    @Test
    void invertedWayBmirrorsX() {
        assertFlip(
                LightPathModel.benchFlipFlags(LightPathModel.SCOPE_INVERTED, LightPathModel.INSERT_B),
                true,
                false,
                "inverted+B");
    }

    @Test
    void fourCombinationsAreAllDistinct() {
        // The two binary factors must cover all four axis-aligned orientations exactly once.
        boolean[][] all = {
            LightPathModel.benchFlipFlags(LightPathModel.SCOPE_UPRIGHT, LightPathModel.INSERT_A),
            LightPathModel.benchFlipFlags(LightPathModel.SCOPE_UPRIGHT, LightPathModel.INSERT_B),
            LightPathModel.benchFlipFlags(LightPathModel.SCOPE_INVERTED, LightPathModel.INSERT_A),
            LightPathModel.benchFlipFlags(LightPathModel.SCOPE_INVERTED, LightPathModel.INSERT_B),
        };
        long distinct = java.util.Arrays.stream(all)
                .map(a -> (a[0] ? "1" : "0") + (a[1] ? "1" : "0"))
                .distinct()
                .count();
        assertEquals(4, distinct, "the four placements must be four distinct orientations");
    }

    @Test
    void nullAndUnknownScopeDefaultToUpright() {
        assertFlip(LightPathModel.benchFlipFlags(null, LightPathModel.INSERT_A), false, false, "null-scope");
        assertFlip(LightPathModel.benchFlipFlags("banana", LightPathModel.INSERT_A), false, false, "garbage-scope");
    }

    @Test
    void slideRotationIsInsertionOnly() {
        // The Stage Map's Stage View uses this (NOT benchFlipFlags): the slide-180 rotation only,
        // independent of scope type. A = identity, B = 180 (both axes). It must equal
        // benchFlipFlags(upright, insertion) so it is a no-op on upright scopes and only drops the
        // scope-face mirror on inverted ones (the OWS3 double-count fix, 2026-08-09).
        assertFlip(LightPathModel.slideRotationFlipFlags(LightPathModel.INSERT_A), false, false, "slide A");
        assertFlip(LightPathModel.slideRotationFlipFlags(LightPathModel.INSERT_B), true, true, "slide B (180)");
        // Independence from scope type: same result regardless of what scope is configured.
        assertArrayEquals(
                LightPathModel.benchFlipFlags(LightPathModel.SCOPE_UPRIGHT, LightPathModel.INSERT_A),
                LightPathModel.slideRotationFlipFlags(LightPathModel.INSERT_A),
                "slide rotation A == benchFlip(upright, A)");
        assertArrayEquals(
                LightPathModel.benchFlipFlags(LightPathModel.SCOPE_UPRIGHT, LightPathModel.INSERT_B),
                LightPathModel.slideRotationFlipFlags(LightPathModel.INSERT_B),
                "slide rotation B == benchFlip(upright, B)");
    }

    @Test
    void opticalFlipFlagsMap() {
        assertFlip(LightPathModel.opticalFlipFlags(LightPathModel.OPTICAL_NONE), false, false, "none");
        assertFlip(LightPathModel.opticalFlipFlags(LightPathModel.OPTICAL_X), true, false, "x");
        assertFlip(LightPathModel.opticalFlipFlags(LightPathModel.OPTICAL_Y), false, true, "y");
        assertFlip(LightPathModel.opticalFlipFlags(LightPathModel.OPTICAL_XY), true, true, "xy");
        assertFlip(LightPathModel.opticalFlipFlags(null), false, false, "null-optical");
    }

    @Test
    void stageViewComposesRotationMirrorAndPolarity() {
        StagePolarity normal = StagePolarity.fromBooleans(false, false);
        StagePolarity invY = StagePolarity.fromBooleans(false, true);
        StagePolarity invXY = StagePolarity.fromBooleans(true, true);
        // PPM (polarity normal, no mirror): the rotation alone.
        assertFlip(LightPathModel.stageViewFlipFlags(LightPathModel.INSERT_A, "none", normal), false, false, "PPM A");
        assertFlip(LightPathModel.stageViewFlipFlags(LightPathModel.INSERT_B, "none", normal), true, true, "PPM B");
        // LC-PolScope (polarity Y): the Y mirror it was given by hand on 2026-10-26 now comes
        // from the polarity, so its config mirror is back to none.
        assertFlip(LightPathModel.stageViewFlipFlags(LightPathModel.INSERT_A, "none", invY), false, true, "LCPS A");
        assertFlip(LightPathModel.stageViewFlipFlags(LightPathModel.INSERT_B, "none", invY), true, false, "LCPS B");
        // OWS3 (polarity X and Y): the 180 it was missing on 2026-10-27.
        assertFlip(LightPathModel.stageViewFlipFlags(LightPathModel.INSERT_A, "none", invXY), true, true, "OWS3 A");
        // A residual mirror still composes on top.
        assertFlip(LightPathModel.stageViewFlipFlags(LightPathModel.INSERT_A, "y", invY), false, false, "residual y");
        assertFlip(LightPathModel.stageViewFlipFlags(LightPathModel.INSERT_A, null, null), false, false, "nulls");
    }

    @Test
    void placementCaptionsNameTheDrawnSide() {
        String[] rig = LightPathModel.placementCaptions(false, true); // the LC-PolScope: A puts the label right
        assertEquals("Label on your right (A)", rig[0]);
        assertEquals("Label on your left (B)", rig[1]);
        String[] other = LightPathModel.placementCaptions(false, false);
        assertEquals("Label on your left (A)", other[0]);
        assertEquals("Label on your right (B)", other[1]);
    }

    @Test
    void aVerticalHolderNamesTheEnds() {
        // The four-slide carrier: slides stand along Y, the label is at the far or the near end,
        // and the two placements are "upside down" to each other, not left and right.
        String[] farForA = LightPathModel.placementCaptions(true, true);
        assertEquals("Label away from you (A)", farForA[0]);
        assertEquals("Label toward you (B)", farForA[1]);
        String[] nearForA = LightPathModel.placementCaptions(true, false);
        assertEquals("Label toward you (A)", nearForA[0]);
        assertEquals("Label away from you (B)", nearForA[1]);
        assertEquals("Label away from you (A)", LightPathModel.genericPlacementCaptions(true)[0]);
        assertEquals("Placement A (as scanned)", LightPathModel.genericPlacementCaptions(false)[0]);
    }

    @Test
    void theQuarterTurnMovesTheLabelToAnEnd() {
        // Ocus40: label on the right of the scan. Rotated 270 (clockwise), the right edge is the
        // top; rotated 90, the bottom. 180 swaps the sides; 0 leaves them.
        assertArrayEquals(new boolean[] {false, true}, LightPathModel.rotatedLabelSide(true, 0));
        assertArrayEquals(new boolean[] {false, false}, LightPathModel.rotatedLabelSide(true, 180));
        assertArrayEquals(new boolean[] {true, true}, LightPathModel.rotatedLabelSide(true, 90));
        assertArrayEquals(new boolean[] {true, false}, LightPathModel.rotatedLabelSide(true, 270));
        assertArrayEquals(new boolean[] {true, true}, LightPathModel.rotatedLabelSide(false, 270));
        assertArrayEquals(new boolean[] {true, false}, LightPathModel.rotatedLabelSide(true, -90));
    }

    @Test
    void placementLabelsAreNeutralAboutTheSide() {
        // Shown in warnings and the profile form on every holder, so they say which placement,
        // not which side: the side depends on the holder and the scanner.
        assertEquals("Placement A (as scanned)", LightPathModel.placementLabel(LightPathModel.INSERT_A));
        assertEquals("Placement B (rotated 180)", LightPathModel.placementLabel(LightPathModel.INSERT_B));
    }
}
