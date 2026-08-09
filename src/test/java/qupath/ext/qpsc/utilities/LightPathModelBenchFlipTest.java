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
}
