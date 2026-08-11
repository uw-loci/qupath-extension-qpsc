package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import org.junit.jupiter.api.Test;

/**
 * Behavior-lock tests for {@link AnnotationHelper#originalToFinalTransform}, the
 * composite that maps a source macro's annotation coordinates into a rotated (+flipped)
 * "(Camera View)" companion's pixel frame. This is the geometry the multi-slide
 * annotation-copy relies on; the transform reproduces the pixel composition
 * {@code createRotatedFlippedDuplicate} applies (rotate first, then flip against the
 * rotated dimensions), so a corner in the source lands on the matching corner in the
 * companion.
 *
 * <p>Convention (matches QuPath's {@code RotatedImageServer} quarter-rotation and
 * {@code ForwardPropagationWorkflow.createFlip}): rotate maps the un-rotated source
 * frame into the rotated frame ({@code wr x hr}); for 90/270 the source axes are
 * swapped. All assertions map explicit corner points, so a sign flip anywhere fails.
 */
class AnnotationTransformTest {

    private static Point2D map(AffineTransform t, double x, double y) {
        return t.transform(new Point2D.Double(x, y), null);
    }

    private static void assertPoint(Point2D actual, double ex, double ey) {
        assertEquals(ex, actual.getX(), 1e-9, "x");
        assertEquals(ey, actual.getY(), 1e-9, "y");
    }

    @Test
    void identity_noRotationNoFlip_isPassThrough() {
        // Source and final frames identical (wr=wo, hr=ho).
        AffineTransform t = AnnotationHelper.originalToFinalTransform(0, 100, 60, false, false);
        assertPoint(map(t, 0, 0), 0, 0);
        assertPoint(map(t, 100, 60), 100, 60);
        assertPoint(map(t, 25, 10), 25, 10);
    }

    @Test
    void rotate180_noFlip_mapsOppositeCorner() {
        // 180 keeps the frame size (wr=100,hr=60); origin -> far corner.
        AffineTransform t = AnnotationHelper.originalToFinalTransform(180, 100, 60, false, false);
        assertPoint(map(t, 0, 0), 100, 60);
        assertPoint(map(t, 100, 60), 0, 0);
    }

    @Test
    void rotate90_noFlip_swapsAxes() {
        // 90: final frame is 60x100 (wr=60,hr=100); source (un-rotated) is 100x60.
        // RotatedImageServer 90 mapping: x' = ho - y, y' = x, with ho = source height = 100.
        AffineTransform t = AnnotationHelper.originalToFinalTransform(90, 60, 100, false, false);
        // Source origin (0,0) -> (ho-0, 0) = (100? ) clamp: ho = wo? For 90, wo=hr=100, ho=wr=60.
        // x' = ho - y = 60 - 0 = 60 ; y' = x = 0.
        assertPoint(map(t, 0, 0), 60, 0);
        // Source (100,0) [top-right of 100x60] -> x'=60-0=60, y'=100.
        assertPoint(map(t, 100, 0), 60, 100);
        // Source (0,60) [bottom-left] -> x'=60-60=0, y'=0.
        assertPoint(map(t, 0, 60), 0, 0);
    }

    @Test
    void rotate270_noFlip_swapsAxesOtherWay() {
        // 270: final frame 60x100 (wr=60,hr=100); source 100x60 (wo=100,ho=60).
        // Mapping: x' = y, y' = wo - x.
        AffineTransform t = AnnotationHelper.originalToFinalTransform(270, 60, 100, false, false);
        assertPoint(map(t, 0, 0), 0, 100); // x'=0, y'=100-0=100
        assertPoint(map(t, 100, 0), 0, 0); // x'=0, y'=100-100=0
        assertPoint(map(t, 0, 60), 60, 100); // x'=60, y'=100
    }

    @Test
    void rotate270_flipX_composesRotateThenFlip() {
        // Rotate 270 into a 60x100 frame, then mirror X within that frame (x -> wr - x = 60 - x).
        AffineTransform rot = AnnotationHelper.originalToFinalTransform(270, 60, 100, false, false);
        AffineTransform rotFlip = AnnotationHelper.originalToFinalTransform(270, 60, 100, true, false);
        // For each sampled source point, flipX must equal (wr - rotX, rotY).
        double[][] pts = {{0, 0}, {100, 0}, {0, 60}, {50, 30}};
        for (double[] p : pts) {
            Point2D r = map(rot, p[0], p[1]);
            Point2D rf = map(rotFlip, p[0], p[1]);
            assertPoint(rf, 60 - r.getX(), r.getY());
        }
    }

    @Test
    void flipXY_noRotation_mirrorsBothAxes() {
        AffineTransform t = AnnotationHelper.originalToFinalTransform(0, 100, 60, true, true);
        assertPoint(map(t, 0, 0), 100, 60);
        assertPoint(map(t, 100, 60), 0, 0);
        assertPoint(map(t, 25, 10), 75, 50);
    }
}
