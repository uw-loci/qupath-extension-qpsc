package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SiftMoveLoop} against a simulated rig: a stage, a target, and how each axis answers. The
 * offset is what a match reports: {@code answer * (position - target)}.
 */
class SiftMoveLoopTest {

    private static final double TOLERANCE = 5;
    private static final double TARGET_X = 1000;
    private static final double TARGET_Y = 2000;

    private static final class Rig implements SiftMoveLoop.Stage, SiftMoveLoop.Meter {
        final double answerX;
        final double answerY;
        double x;
        double y;
        int moves;
        int matches;
        int matchesUntilLost = Integer.MAX_VALUE;

        Rig(double startX, double startY, double answerX, double answerY) {
            this.x = startX;
            this.y = startY;
            this.answerX = answerX;
            this.answerY = answerY;
        }

        double[] offset() {
            return new double[] {answerX * (x - TARGET_X), answerY * (y - TARGET_Y)};
        }

        @Override
        public double[] position() {
            return new double[] {x, y};
        }

        @Override
        public void moveTo(double newX, double newY) {
            x = newX;
            y = newY;
            moves++;
        }

        @Override
        public double[] measure() {
            return ++matches > matchesUntilLost ? null : offset();
        }

        SiftMoveLoop.Landing run(boolean flipX, boolean flipY) throws Exception {
            return SiftMoveLoop.run(offset(), flipX, flipY, TOLERANCE, this, this);
        }
    }

    @Test
    void aRigThatAnswersAsExpectedTakesOneMoveAndOneCheck() throws Exception {
        Rig rig = new Rig(1174, 2090, 1, 1);
        SiftMoveLoop.Landing landing = rig.run(false, false);

        assertTrue(landing.verified());
        assertFalse(landing.axisReversed());
        assertEquals(1, rig.moves);
        assertEquals(1, rig.matches);
        assertEquals(TARGET_X, rig.x, 1e-9);
        assertEquals(TARGET_Y, rig.y, 1e-9);
    }

    @Test
    void xReversedAsOnOws3LandsOnTheTarget() throws Exception {
        // 2026-10-27: X went the wrong way at the same magnitude, Y was right
        Rig rig = new Rig(1163, 1892, -1, 1);
        SiftMoveLoop.Landing landing = rig.run(false, false);

        assertTrue(landing.verified());
        assertTrue(landing.axisReversed());
        assertEquals(TARGET_X, rig.x, 1e-9);
        assertEquals(TARGET_Y, rig.y, 1e-9);
        assertEquals(2, rig.moves, "one wrong move, one correction");
    }

    @Test
    void bothAxesReversedLandOnTheTarget() throws Exception {
        Rig rig = new Rig(700, 2300, -1, -1);
        assertTrue(rig.run(false, false).verified());
        assertEquals(TARGET_X, rig.x, 1e-9);
        assertEquals(TARGET_Y, rig.y, 1e-9);
    }

    @Test
    void aServerFlipIsMeasuredThroughToo() throws Exception {
        Rig rig = new Rig(1200, 1900, -1, 1);
        assertTrue(rig.run(true, false).verified());
        assertEquals(TARGET_X, rig.x, 1e-9);
        assertEquals(TARGET_Y, rig.y, 1e-9);
    }

    @Test
    void aReversedAxisIsFoundEvenWhenTheOtherStartedOnTarget() throws Exception {
        Rig rig = new Rig(1300, 2001, -1, 1);
        assertTrue(rig.run(false, false).verified());
        assertEquals(TARGET_X, rig.x, 1e-9);
        assertEquals(TARGET_Y, rig.y, 2);
    }

    @Test
    void anOffsetAlreadyWithinToleranceIsNotCheckedAgain() throws Exception {
        Rig rig = new Rig(1003, 1998, 1, 1);
        assertTrue(rig.run(false, false).verified());
        assertEquals(0, rig.matches);
    }

    @Test
    void aLostMatchReturnsToTheStartUnverified() throws Exception {
        Rig rig = new Rig(1300, 2300, -1, -1);
        rig.matchesUntilLost = 0;
        assertFalse(rig.run(false, false).verified());
        assertEquals(1300, rig.x, 1e-9);
        assertEquals(2300, rig.y, 1e-9);
    }

    @Test
    void matchesThatDisagreeWithTheMoveAreNotTrusted() throws Exception {
        // A second match elsewhere on the slide: the offset does not follow the stage
        Rig rig = new Rig(1300, 2300, 3, 3);
        assertFalse(rig.run(false, false).verified());
        assertEquals(1300, rig.x, 1e-9);
        assertEquals(2300, rig.y, 1e-9);
    }

    @Test
    void theRegionCenterShiftIsZeroWhenTheRegionIsCenteredOnTheTile() {
        assertEquals(0, SiftAutoAlignHelper.regionCenterShiftUm(100, 500, 200, 300, 0.5, false), 1e-9);
    }

    @Test
    void aRegionClippedAtTheImageEdgeReportsItsShift() {
        // Tile 300 px wide at x=50 with a 160 px margin: clipped at 0, 110 px short on the left
        double regionStart = 0;
        double regionSize = 300 + 160 + 50;
        double shift = SiftAutoAlignHelper.regionCenterShiftUm(regionStart, regionSize, 50, 300, 0.5, false);
        assertEquals(27.5, shift, 1e-9, "(255 - 200) px * 0.5 um/px");
        assertEquals(-27.5, SiftAutoAlignHelper.regionCenterShiftUm(0, 510, 50, 300, 0.5, true), 1e-9);
    }
}
