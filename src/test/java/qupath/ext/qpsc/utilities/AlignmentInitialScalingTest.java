package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import org.junit.jupiter.api.Test;

/**
 * The alignment's initial pixel -> stage scaling must move between two points of a Camera View
 * image exactly as double-click-to-center would in the Live Viewer. Reading stage polarity alone
 * sent the LC-PolScope's second alignment tile the wrong way in Y once Calibrate Directions had
 * put a Y flip in the camera orientation (2026-10-19).
 */
class AlignmentInitialScalingTest {

    private static final double PX = 0.5;

    @Test
    void matchesDoubleClickToCenterForEveryPolarityAndOrientation() {
        for (StagePolarity polarity : StagePolarity.values()) {
            for (CameraOrientation orientation : CameraOrientation.values()) {
                StageImageTransform sit = new StageImageTransform(polarity, orientation);
                AffineTransform scaling = TransformationFunctions.setupAffineTransformation(PX, sit);
                double dx = 120, dy = -45;
                Point2D moved = scaling.transform(new Point2D.Double(dx, dy), null);
                double[] click = sit.clickOffsetToMmTarget(0, 0, dx, dy, PX, PX);
                String label = polarity + "/" + orientation;
                assertEquals(click[0], moved.getX(), 1e-9, label);
                assertEquals(click[1], moved.getY(), 1e-9, label);
            }
        }
    }

    @Test
    void normalCameraOrientationKeepsThePolarityOnlyScaling() {
        for (StagePolarity polarity : StagePolarity.values()) {
            AffineTransform scaling = TransformationFunctions.setupAffineTransformation(
                    PX, new StageImageTransform(polarity, CameraOrientation.NORMAL));
            assertEquals(polarity.invertX ? -PX : PX, scaling.getScaleX(), 1e-12, polarity.name());
            assertEquals(polarity.invertY ? -PX : PX, scaling.getScaleY(), 1e-12, polarity.name());
            assertEquals(0, scaling.getShearX(), 1e-12);
            assertEquals(0, scaling.getShearY(), 1e-12);
        }
    }

    @Test
    void cameraYFlipReversesTheYStep() {
        AffineTransform scaling = TransformationFunctions.setupAffineTransformation(
                PX, new StageImageTransform(StagePolarity.NORMAL, CameraOrientation.FLIP_V));
        assertEquals(PX, scaling.getScaleX(), 1e-12);
        assertEquals(-PX, scaling.getScaleY(), 1e-12);
    }
}
