package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.ui.StageDirectionCalibrationDialog.CalibrationResult;
import qupath.ext.qpsc.ui.StageDirectionCalibrationDialog.Direction;
import qupath.ext.qpsc.utilities.CameraOrientation;
import qupath.ext.qpsc.utilities.StageImageTransform;
import qupath.ext.qpsc.utilities.StagePolarity;

/**
 * The calibration asks which way the TISSUE moved, and solves for the orientation that makes
 * double-click-to-center put the clicked point in the middle.
 *
 * <p>2026-10-19: the solver compared the answer with the view's pan direction, the opposite of
 * the tissue's motion, so truthful answers came out 180 degrees off and double-click went the
 * wrong way on both axes on the LC-PolScope. The demo microscope, whose double-click is right
 * with NORMAL/NORMAL, makes the tissue slide left and up for +X and +Y.
 */
class StageDirectionBackSolveTest {

    @Test
    void theDemoMicroscopeSolvesToNormal() {
        CalibrationResult r =
                StageDirectionCalibrationDialog.backSolve(Direction.LEFT, Direction.UP, StagePolarity.NORMAL);
        assertNotNull(r);
        assertEquals(CameraOrientation.NORMAL, r.orientation());
    }

    @Test
    void everyOrientationIsRecoveredFromTheTissueMotionItProduces() {
        for (StagePolarity polarity : StagePolarity.values()) {
            for (CameraOrientation truth : CameraOrientation.values()) {
                Direction x = tissueMotion(polarity, truth, 1, 0);
                Direction y = tissueMotion(polarity, truth, 0, 1);
                CalibrationResult r = StageDirectionCalibrationDialog.backSolve(x, y, polarity);
                assertNotNull(r, polarity + "/" + truth);
                assertEquals(truth, r.orientation(), polarity + "/" + truth);
            }
        }
    }

    @Test
    void theSolvedOrientationMakesDoubleClickCentreTheClickedTissue() {
        // Click right of centre: the stage must move so that tissue comes to the centre, i.e.
        // the tissue must then slide LEFT -- which is what a +X answer of LEFT means.
        for (StagePolarity polarity : StagePolarity.values()) {
            for (CameraOrientation truth : CameraOrientation.values()) {
                CalibrationResult r = StageDirectionCalibrationDialog.backSolve(
                        tissueMotion(polarity, truth, 1, 0), tissueMotion(polarity, truth, 0, 1), polarity);
                double[] mm = new StageImageTransform(r.polarity(), r.orientation()).screenPanDeltaToMmDelta(1, 0);
                Direction slid = tissueMotion(polarity, truth, mm[0], mm[1]);
                assertEquals(Direction.LEFT, slid, polarity + "/" + truth);
            }
        }
    }

    /** What a person sees for an mm command on hardware whose true orientation is {@code truth}. */
    private static Direction tissueMotion(StagePolarity sp, CameraOrientation truth, double mmDx, double mmDy) {
        double[] sample = sp.mmToSampleDelta(mmDx, mmDy);
        double[] pan = truth.sampleToDisplay(sample[0], sample[1]);
        double cx = -pan[0], cy = -pan[1];
        for (Direction d : Direction.values()) {
            if (Math.abs(d.sx - Math.signum(cx)) < 1e-6 && Math.abs(d.sy - Math.signum(cy)) < 1e-6) {
                return d;
            }
        }
        throw new AssertionError("not a cardinal motion: " + cx + ", " + cy);
    }
}
