package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/**
 * The direction hint sent with a tissue search: which way to look when a predicted landmark
 * lands on blank glass.
 *
 * <p>The tile grid only exists over annotations, so its centre is where the tissue is. What
 * these tests pin down is that the hint comes back in STAGE space (the server steps along it
 * in micrometres) and that a degenerate case returns null rather than a confident bearing
 * built out of rounding noise.
 */
class TissueDirectionHintTest {

    private static PathObject tileAt(double x, double y) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x - 5, y - 5, 10, 10, ImagePlane.getDefaultPlane()));
    }

    private static List<PathObject> grid() {
        List<PathObject> tiles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                tiles.add(tileAt(100 + i * 100, 100 + j * 100));
            }
        }
        return tiles; // centroid = (200, 200)
    }

    @Test
    void pointsFromTheStartTowardTheGridCentre() {
        // Identity transform: stage micrometres equal QuPath pixels, so the hint is just the
        // vector to the grid centre.
        double[] hint = SlotJumpAutofocus.tissueDirectionHint(grid(), new double[] {0, 0}, new AffineTransform());
        assertNotNull(hint);
        assertEquals(200.0, hint[0], 1e-6);
        assertEquals(200.0, hint[1], 1e-6);
    }

    @Test
    void hintIsInStageSpaceNotPixelSpace() {
        // The server steps along this vector in micrometres, so the pixel-size scaling has to
        // already be applied. A 0.5 um/px transform halves it.
        AffineTransform scale = AffineTransform.getScaleInstance(0.5, 0.5);
        double[] hint = SlotJumpAutofocus.tissueDirectionHint(grid(), new double[] {0, 0}, scale);
        assertNotNull(hint);
        assertEquals(100.0, hint[0], 1e-6);
        assertEquals(100.0, hint[1], 1e-6);
    }

    @Test
    void respectsAnAxisFlippingTransform() {
        // A stage whose Y runs opposite the image must send the hint the other way, or the
        // search walks away from the tissue it is looking for.
        AffineTransform flipY = new AffineTransform(1, 0, 0, -1, 0, 0);
        double[] hint = SlotJumpAutofocus.tissueDirectionHint(grid(), new double[] {0, 0}, flipY);
        assertNotNull(hint);
        assertEquals(200.0, hint[0], 1e-6);
        assertEquals(-200.0, hint[1], 1e-6);
    }

    @Test
    void returnsNullAtTheGridCentre() {
        // Already where the hint would point: no bearing is better than any other, and the
        // caller should let the server sweep the compass instead.
        assertNull(SlotJumpAutofocus.tissueDirectionHint(grid(), new double[] {200, 200}, new AffineTransform()));
    }

    @Test
    void returnsNullBelowOneMicrometre() {
        // Sub-micrometre separation is the same place as far as the stage is concerned;
        // normalising it would amplify rounding into a confident direction.
        assertNull(SlotJumpAutofocus.tissueDirectionHint(grid(), new double[] {200.4, 200.4}, new AffineTransform()));
    }

    @Test
    void returnsNullWithoutTiles() {
        assertNull(SlotJumpAutofocus.tissueDirectionHint(List.of(), new double[] {0, 0}, new AffineTransform()));
        assertNull(SlotJumpAutofocus.tissueDirectionHint(null, new double[] {0, 0}, new AffineTransform()));
    }

    @Test
    void returnsNullWithoutATransformOrAStartPoint() {
        assertNull(SlotJumpAutofocus.tissueDirectionHint(grid(), null, new AffineTransform()));
        assertNull(SlotJumpAutofocus.tissueDirectionHint(grid(), new double[] {0, 0}, null));
    }

    @Test
    void ignoresTilesWithNoRoi() {
        List<PathObject> tiles = new ArrayList<>(grid());
        tiles.add(PathObjects.createDetectionObject(ROIs.createEmptyROI()));
        double[] hint = SlotJumpAutofocus.tissueDirectionHint(tiles, new double[] {0, 0}, new AffineTransform());
        assertNotNull(hint);
        assertEquals(200.0, hint[0], 1e-6);
        assertEquals(200.0, hint[1], 1e-6);
    }

    // ---- the gate: which callers get a search at all ---------------------------

    @org.junit.jupiter.api.AfterEach
    void disarm() {
        qupath.ext.qpsc.ui.AutoAdvanceController.disarmSession();
    }

    @Test
    void noSearchOutsideAnAutomaticBatch() {
        // In a manual run the operator is watching the live view; a stage wandering several
        // hundred micrometres unbidden is worse than the problem it solves.
        qupath.ext.qpsc.ui.AutoAdvanceController.disarmSession();
        assertNull(SlotJumpAutofocus.tissueSearchForFirstLandmark(grid(), new double[] {0, 0}, new AffineTransform()));
    }

    @Test
    void noSearchOnASlideTheOperatorHasTakenOver() {
        qupath.ext.qpsc.ui.AutoAdvanceController.armSession(
                qupath.ext.qpsc.preferences.MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 5);
        qupath.ext.qpsc.ui.AutoAdvanceController.overrideCurrentSlide("operator took over");
        assertNull(SlotJumpAutofocus.tissueSearchForFirstLandmark(grid(), new double[] {0, 0}, new AffineTransform()));
    }

    @Test
    void armedBatchSearchesWithTheHint() {
        qupath.ext.qpsc.ui.AutoAdvanceController.armSession(
                qupath.ext.qpsc.preferences.MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 5);
        var search = SlotJumpAutofocus.tissueSearchForFirstLandmark(grid(), new double[] {0, 0}, new AffineTransform());
        assertNotNull(search);
        assertEquals(200.0, search.dirX(), 1e-6);
        assertEquals(200.0, search.dirY(), 1e-6);
    }

    @Test
    void armedBatchStillSearchesWhenNoBearingIsAvailable() {
        // Standing on the grid centre: no direction is better than another, but blank glass is
        // still possible, so the search runs unhinted and the server sweeps the compass.
        qupath.ext.qpsc.ui.AutoAdvanceController.armSession(
                qupath.ext.qpsc.preferences.MultiSlideAcquisitionMode.FULLY_AUTOMATIC, 5);
        var search =
                SlotJumpAutofocus.tissueSearchForFirstLandmark(grid(), new double[] {200, 200}, new AffineTransform());
        assertNotNull(search, "an unhinted search is still worth running");
        assertTrue(Double.isNaN(search.dirX()));
        assertTrue(Double.isNaN(search.dirY()));
    }
}
