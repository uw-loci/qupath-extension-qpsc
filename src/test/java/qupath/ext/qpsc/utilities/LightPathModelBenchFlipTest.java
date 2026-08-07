package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LightPathModel#benchFlipFlags(String, String, String)} -- the deterministic
 * reduction of the two slide-placement factors (scope type + slide insertion, with the inverted
 * turn-over axis) into a {flipX, flipY} bench flip. These pin the sign table so the Stage Map's
 * Stage View and the setup wizard can never silently diverge, and mirror the light-path simulator:
 *
 * <pre>
 *   bench = scopeFace o insertion   (axis-aligned, diagonal-only)
 *   upright:      identity            inverted-vertical: mirror Y   inverted-horizontal: mirror X
 *   insertion A:  identity            insertion B (180): mirror X and mirror Y
 * </pre>
 */
class LightPathModelBenchFlipTest {

    private static void assertFlip(boolean[] got, boolean x, boolean y, String label) {
        assertEquals(x, got[0], label + " flipX");
        assertEquals(y, got[1], label + " flipY");
    }

    @Test
    void uprightWayAisIdentity() {
        // The historical inert default: Stage View == Camera View.
        assertFlip(
                LightPathModel.benchFlipFlags(
                        LightPathModel.SCOPE_UPRIGHT, LightPathModel.INSERT_A, LightPathModel.AXIS_VERTICAL),
                false,
                false,
                "upright+A");
    }

    @Test
    void uprightWayBis180() {
        assertFlip(
                LightPathModel.benchFlipFlags(
                        LightPathModel.SCOPE_UPRIGHT, LightPathModel.INSERT_B, LightPathModel.AXIS_VERTICAL),
                true,
                true,
                "upright+B");
    }

    @Test
    void invertedVerticalMirrorsY() {
        assertFlip(
                LightPathModel.benchFlipFlags(
                        LightPathModel.SCOPE_INVERTED, LightPathModel.INSERT_A, LightPathModel.AXIS_VERTICAL),
                false,
                true,
                "inverted-V+A");
    }

    @Test
    void invertedHorizontalMirrorsX() {
        assertFlip(
                LightPathModel.benchFlipFlags(
                        LightPathModel.SCOPE_INVERTED, LightPathModel.INSERT_A, LightPathModel.AXIS_HORIZONTAL),
                true,
                false,
                "inverted-H+A");
    }

    @Test
    void invertedVerticalWayBcancelsToX() {
        // mirrorY o 180 = mirrorX: flipX only.
        assertFlip(
                LightPathModel.benchFlipFlags(
                        LightPathModel.SCOPE_INVERTED, LightPathModel.INSERT_B, LightPathModel.AXIS_VERTICAL),
                true,
                false,
                "inverted-V+B");
    }

    @Test
    void invertedHorizontalWayBcancelsToY() {
        // mirrorX o 180 = mirrorY: flipY only.
        assertFlip(
                LightPathModel.benchFlipFlags(
                        LightPathModel.SCOPE_INVERTED, LightPathModel.INSERT_B, LightPathModel.AXIS_HORIZONTAL),
                false,
                true,
                "inverted-H+B");
    }

    @Test
    void nullAndUnknownScopeDefaultToUpright() {
        // Defensive: an unset/garbage scope type must not throw and must read as upright (identity).
        assertFlip(
                LightPathModel.benchFlipFlags(null, LightPathModel.INSERT_A, LightPathModel.AXIS_VERTICAL),
                false,
                false,
                "null-scope");
        assertFlip(
                LightPathModel.benchFlipFlags("banana", LightPathModel.INSERT_A, LightPathModel.AXIS_VERTICAL),
                false,
                false,
                "garbage-scope");
    }
}
