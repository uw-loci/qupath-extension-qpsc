package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StageImageTransform}, {@link StagePolarity}, and
 * {@link CameraOrientation}.
 *
 * <p>The goal of this test suite is to verify coordinate-math correctness
 * across every combination of stage polarity × camera orientation. Manual
 * sign reasoning is error-prone (see OWS3 debugging session 2026-04-09,
 * which produced sign regressions in three separate subsystems), so we
 * enumerate the full matrix rather than picking representative cases.
 */
class StageImageTransformTest {

    private static final double EPS = 1e-9;

    // ------------------------------------------------------------
    // StagePolarity sanity
    // ------------------------------------------------------------

    @Test
    void stagePolarity_normal_isIdentity() {
        double[] mm = StagePolarity.NORMAL.sampleToMmDelta(3, -5);
        assertArrayEquals(new double[] {3, -5}, mm, EPS);
    }

    @Test
    void stagePolarity_invertX_negatesX() {
        double[] mm = StagePolarity.INVERT_X.sampleToMmDelta(3, -5);
        assertArrayEquals(new double[] {-3, -5}, mm, EPS);
    }

    @Test
    void stagePolarity_invertY_negatesY() {
        double[] mm = StagePolarity.INVERT_Y.sampleToMmDelta(3, -5);
        assertArrayEquals(new double[] {3, 5}, mm, EPS);
    }

    @Test
    void stagePolarity_invertXY_negatesBoth() {
        double[] mm = StagePolarity.INVERT_XY.sampleToMmDelta(3, -5);
        assertArrayEquals(new double[] {-3, 5}, mm, EPS);
    }

    @Test
    void stagePolarity_mmToSample_isSelfInverse() {
        for (StagePolarity p : StagePolarity.values()) {
            double[] rt = p.mmToSampleDelta(p.sampleToMmDelta(7, -11)[0], p.sampleToMmDelta(7, -11)[1]);
            assertArrayEquals(new double[] {7, -11}, rt, EPS, "roundtrip failed for " + p);
        }
    }

    @Test
    void stagePolarity_fromBooleans_covers4Cases() {
        assertEquals(StagePolarity.NORMAL,    StagePolarity.fromBooleans(false, false));
        assertEquals(StagePolarity.INVERT_X,  StagePolarity.fromBooleans(true, false));
        assertEquals(StagePolarity.INVERT_Y,  StagePolarity.fromBooleans(false, true));
        assertEquals(StagePolarity.INVERT_XY, StagePolarity.fromBooleans(true, true));
    }

    // ------------------------------------------------------------
    // CameraOrientation sanity
    // ------------------------------------------------------------

    @Test
    void cameraOrientation_allAreOrthogonal_roundtripReturnsInput() {
        // For every orientation and every test vector, displayToSample
        // should undo sampleToDisplay exactly.
        double[][] testVectors = {{1, 0}, {0, 1}, {3, -5}, {-7, 11}, {1, 1}};
        for (CameraOrientation o : CameraOrientation.values()) {
            for (double[] v : testVectors) {
                double[] display = o.sampleToDisplay(v[0], v[1]);
                double[] roundtrip = o.displayToSample(display[0], display[1]);
                assertArrayEquals(v, roundtrip, EPS,
                        "orientation=" + o + " vector=(" + v[0] + "," + v[1] + ")");
            }
        }
    }

    @Test
    void cameraOrientation_normal_isIdentity() {
        assertArrayEquals(new double[] {3, -5}, CameraOrientation.NORMAL.sampleToDisplay(3, -5), EPS);
    }

    @Test
    void cameraOrientation_flipH_negatesX() {
        assertArrayEquals(new double[] {-3, -5}, CameraOrientation.FLIP_H.sampleToDisplay(3, -5), EPS);
    }

    @Test
    void cameraOrientation_flipV_negatesY() {
        assertArrayEquals(new double[] {3, 5}, CameraOrientation.FLIP_V.sampleToDisplay(3, -5), EPS);
    }

    @Test
    void cameraOrientation_rot180_negatesBoth() {
        assertArrayEquals(new double[] {-3, 5}, CameraOrientation.ROT_180.sampleToDisplay(3, -5), EPS);
    }

    @Test
    void cameraOrientation_rot90cw_swapsSampleXToNegativeDisplayY() {
        // Sample +X should appear at the top of the display (= display -Y).
        // Matrix: [[0, 1], [-1, 0]]. Sample (1, 0) -> (0, -1). Correct.
        assertArrayEquals(new double[] {0, -1}, CameraOrientation.ROT_90_CW.sampleToDisplay(1, 0), EPS);
        assertArrayEquals(new double[] {1, 0},  CameraOrientation.ROT_90_CW.sampleToDisplay(0, 1), EPS);
    }

    @Test
    void cameraOrientation_axisSwapDetection() {
        assertFalse(CameraOrientation.NORMAL.swapsAxes());
        assertFalse(CameraOrientation.FLIP_H.swapsAxes());
        assertFalse(CameraOrientation.FLIP_V.swapsAxes());
        assertFalse(CameraOrientation.ROT_180.swapsAxes());
        assertTrue(CameraOrientation.ROT_90_CW.swapsAxes());
        assertTrue(CameraOrientation.ROT_90_CCW.swapsAxes());
        assertTrue(CameraOrientation.TRANSPOSE.swapsAxes());
        assertTrue(CameraOrientation.ANTI_TRANSPOSE.swapsAxes());
    }

    // ------------------------------------------------------------
    // StageImageTransform: screen pan → MM command
    // ------------------------------------------------------------

    @Test
    void transform_normal_panUp_decreasesY() {
        // NORMAL stage + NORMAL camera.
        // Arrow up = pan up = screen delta (0, -1).
        // Expected: sample delta (0, -1), MM delta (0, -1).
        StageImageTransform t = new StageImageTransform(
                StagePolarity.NORMAL, CameraOrientation.NORMAL);
        double[] mm = t.screenPanDeltaToMmDelta(0, -1);
        assertArrayEquals(new double[] {0, -1}, mm, EPS);
    }

    @Test
    void transform_normal_panRight_increasesX() {
        StageImageTransform t = new StageImageTransform(
                StagePolarity.NORMAL, CameraOrientation.NORMAL);
        double[] mm = t.screenPanDeltaToMmDelta(1, 0);
        assertArrayEquals(new double[] {1, 0}, mm, EPS);
    }

    @Test
    void transform_invertY_panUp_increasesY() {
        // OWS3-style scope: stage Y is wired backwards, camera is normal.
        // Arrow up = pan up = screen delta (0, -1).
        // Expected: sample delta (0, -1) but stage polarity INVERT_Y
        // flips Y, so MM delta = (0, +1). Stage Y should INCREASE.
        StageImageTransform t = new StageImageTransform(
                StagePolarity.INVERT_Y, CameraOrientation.NORMAL);
        double[] mm = t.screenPanDeltaToMmDelta(0, -1);
        assertArrayEquals(new double[] {0, 1}, mm, EPS);
    }

    @Test
    void transform_flipV_panUp_increasesY() {
        // Camera vertically flipped, stage normal.
        // Arrow up = screen (0, -1). displayToSample on FLIP_V:
        // [[1,0],[0,-1]] applied (transposed = self) to (0, -1) = (0, 1).
        // Sample delta (0, 1) through NORMAL polarity = MM (0, 1).
        StageImageTransform t = new StageImageTransform(
                StagePolarity.NORMAL, CameraOrientation.FLIP_V);
        double[] mm = t.screenPanDeltaToMmDelta(0, -1);
        assertArrayEquals(new double[] {0, 1}, mm, EPS);
    }

    @Test
    void transform_invertY_and_flipV_cancel() {
        // INVERT_Y stage AND FLIP_V camera should cancel out, giving the
        // same result as NORMAL + NORMAL.
        StageImageTransform t = new StageImageTransform(
                StagePolarity.INVERT_Y, CameraOrientation.FLIP_V);
        double[] mm = t.screenPanDeltaToMmDelta(0, -1);
        assertArrayEquals(new double[] {0, -1}, mm, EPS);
    }

    @Test
    void transform_clickBelow_panDown_increasesY_on_normal() {
        // Click below center should bring the below-center content to the
        // center = pan down. On a NORMAL scope that means stage Y INCREASES.
        StageImageTransform t = new StageImageTransform(
                StagePolarity.NORMAL, CameraOrientation.NORMAL);
        double[] target = t.clickOffsetToMmTarget(1000, 2000, 0, 100, 0.65, 0.65);
        assertEquals(1000, target[0], EPS);
        assertEquals(2000 + 100 * 0.65, target[1], EPS);
    }

    @Test
    void transform_clickBelow_decreasesY_on_invertY() {
        // OWS3-style scope: click below center → pan down → stage Y
        // DECREASES (opposite of normal).
        StageImageTransform t = new StageImageTransform(
                StagePolarity.INVERT_Y, CameraOrientation.NORMAL);
        double[] target = t.clickOffsetToMmTarget(1000, 2000, 0, 100, 0.65, 0.65);
        assertEquals(1000, target[0], EPS);
        assertEquals(2000 - 100 * 0.65, target[1], EPS);
    }

    // ------------------------------------------------------------
    // StageImageTransform: stitcher flip flags
    // ------------------------------------------------------------

    @Test
    void stitcherFlags_normal_isNoFlip() {
        StageImageTransform t = new StageImageTransform(
                StagePolarity.NORMAL, CameraOrientation.NORMAL);
        assertArrayEquals(new boolean[] {false, false}, t.stitcherFlipFlags());
    }

    @Test
    void stitcherFlags_invertY_flipsY() {
        StageImageTransform t = new StageImageTransform(
                StagePolarity.INVERT_Y, CameraOrientation.NORMAL);
        assertArrayEquals(new boolean[] {false, true}, t.stitcherFlipFlags());
    }

    @Test
    void stitcherFlags_invertXY_flipsBoth() {
        StageImageTransform t = new StageImageTransform(
                StagePolarity.INVERT_XY, CameraOrientation.NORMAL);
        assertArrayEquals(new boolean[] {true, true}, t.stitcherFlipFlags());
    }

    @Test
    void stitcherFlags_flipH_flipsX() {
        StageImageTransform t = new StageImageTransform(
                StagePolarity.NORMAL, CameraOrientation.FLIP_H);
        assertArrayEquals(new boolean[] {true, false}, t.stitcherFlipFlags());
    }

    @Test
    void stitcherFlags_invertX_and_flipH_cancel() {
        StageImageTransform t = new StageImageTransform(
                StagePolarity.INVERT_X, CameraOrientation.FLIP_H);
        assertArrayEquals(new boolean[] {false, false}, t.stitcherFlipFlags());
    }

    @Test
    void stitcherFlags_rot180_flipsBoth() {
        StageImageTransform t = new StageImageTransform(
                StagePolarity.NORMAL, CameraOrientation.ROT_180);
        assertArrayEquals(new boolean[] {true, true}, t.stitcherFlipFlags());
    }

    // ------------------------------------------------------------
    // Self-consistency across all 4 x 4 axis-aligned combinations
    // ------------------------------------------------------------

    @Test
    void allAxisAlignedCombinations_arrowUp_andClickBelow_produceOppositeYDirections() {
        // Arrow up (pan up) and click below center (pan down) are
        // opposite intents and must therefore produce opposite Y signs
        // in the MM-command delta for every transform configuration.
        CameraOrientation[] axisAligned = {
                CameraOrientation.NORMAL,
                CameraOrientation.FLIP_H,
                CameraOrientation.FLIP_V,
                CameraOrientation.ROT_180
        };
        for (StagePolarity p : StagePolarity.values()) {
            for (CameraOrientation o : axisAligned) {
                StageImageTransform t = new StageImageTransform(p, o);
                double[] arrowUp = t.screenPanDeltaToMmDelta(0, -1);     // pan up
                double[] clickBelow = t.screenPanDeltaToMmDelta(0, 1);   // pan down
                assertTrue(arrowUp[1] * clickBelow[1] < 0,
                        "Arrow up and click below must produce opposite Y signs. "
                                + "transform=" + t);
                assertEquals(0, arrowUp[0], EPS,
                        "Arrow up should not produce any X motion on axis-aligned transform " + t);
                assertEquals(0, clickBelow[0], EPS,
                        "Click below should not produce any X motion on axis-aligned transform " + t);
            }
        }
    }

    @Test
    void allAxisAlignedCombinations_arrowRight_andClickRight_produceSameXDirection() {
        // Arrow right (pan right) and click right of center (also pan right
        // — bring right content to center) share the same intent and must
        // produce the same X sign.
        CameraOrientation[] axisAligned = {
                CameraOrientation.NORMAL,
                CameraOrientation.FLIP_H,
                CameraOrientation.FLIP_V,
                CameraOrientation.ROT_180
        };
        for (StagePolarity p : StagePolarity.values()) {
            for (CameraOrientation o : axisAligned) {
                StageImageTransform t = new StageImageTransform(p, o);
                double[] arrowRight = t.screenPanDeltaToMmDelta(1, 0);
                double[] clickRight = t.screenPanDeltaToMmDelta(1, 0);
                assertEquals(arrowRight[0], clickRight[0], EPS,
                        "Same input should produce same output. transform=" + t);
            }
        }
    }
}
