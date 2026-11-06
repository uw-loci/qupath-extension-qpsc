package qupath.ext.qpsc.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Unit tests for the pure (I/O-free) pieces of the multi-slide run-time estimate: the shared
 * tiles-per-annotation count (the tiler's own grid and keep-test) and the batch summary
 * formatting.
 */
class MultiSlideEstimateTest {

    private static ROI square(double size) {
        return ROIs.createRectangleROI(0, 0, size, size, ImagePlane.getDefaultPlane());
    }

    @Test
    void tileCount_rectangleIsTheBufferedGridThatTouchesIt() {
        // Buffered grid from -50 at a 100 step: 12 columns, the 12th starting at 1050, past the
        // square's edge, so 11 x 11 tiles touch it.
        assertEquals(121, TilingUtilities.countAnnotationTiles(square(1000), 100, 100, 0));
    }

    @Test
    void tileCount_followsTheShapeNotItsBoundingBox() {
        // 2026-11-06: an annotation covering about half its bounding box was estimated at the
        // box's 4,200 tiles while it held 2,210. A triangle is half its box.
        ROI triangle = ROIs.createPolygonROI(
                new double[] {0, 1000, 0}, new double[] {0, 0, 1000}, ImagePlane.getDefaultPlane());
        int half = TilingUtilities.countAnnotationTiles(triangle, 100, 100, 0);
        int whole = TilingUtilities.countAnnotationTiles(square(1000), 100, 100, 0);
        assertTrue(half > whole * 0.45 && half < whole * 0.65, half + " of " + whole);
    }

    @Test
    void tileCount_overlapShrinksTheStep() {
        assertTrue(TilingUtilities.countAnnotationTiles(square(1000), 100, 100, 10)
                > TilingUtilities.countAnnotationTiles(square(1000), 100, 100, 0));
    }

    @Test
    void tileCount_degenerateInputsGiveNoTiles() {
        assertEquals(0, TilingUtilities.countAnnotationTiles(null, 100, 100, 0));
        assertEquals(0, TilingUtilities.countAnnotationTiles(square(1000), 0, 0, 0));
        // 100% overlap is a zero step: guarded, not an endless grid
        assertEquals(0, TilingUtilities.countAnnotationTiles(square(1000), 100, 100, 100));
    }

    @Test
    void batchSummary_learnedVsRough() {
        var learned = new MultiSlideAcquisitionEstimator.BatchEstimate(List.of(), 4, 3100, 12400, 6000, true);
        String s = learned.summary();
        assertTrue(s.contains("4 slides"), s);
        assertTrue(s.contains("3,100 tiles"), s);
        assertTrue(s.contains("measured timing"), s);
        assertTrue(s.contains("1h"), s); // 6000 s = 1h 40m

        var rough = new MultiSlideAcquisitionEstimator.BatchEstimate(List.of(), 1, 50, 200, 90, false);
        String r = rough.summary();
        assertTrue(r.contains("1 slide,"), r); // singular, no trailing 's'
        assertTrue(r.contains("no measured timing yet"), r);
    }

    @Test
    void slotCost_manySmallRegionsCostMoreThanOneBigRegion() {
        // Same 100 files, same per-file time: 20 regions must cost 19 extra region-focus passes
        // more than 1 region (the whole point of costing per-annotation, not just per-tile).
        double twenty = MultiSlideAcquisitionEstimator.slotSec(100, 20, 1000);
        double one = MultiSlideAcquisitionEstimator.slotSec(100, 1, 1000);
        assertTrue(twenty > one, "20 regions should cost more than 1 region at equal tiles");
        assertEquals(
                19 * MultiSlideAcquisitionEstimator.PER_ANNOTATION_OVERHEAD_S,
                twenty - one,
                1e-9,
                "difference should be exactly the per-region overhead x (20-1)");
    }

    @Test
    void slotCost_scalesWithFilesAndClampsNegatives() {
        double base = MultiSlideAcquisitionEstimator.slotSec(0, 0, 1000);
        // 0 files, 0 regions -> just the per-slide startup.
        assertEquals(MultiSlideAcquisitionEstimator.PER_SLIDE_OVERHEAD_S, base, 1e-9);
        // 100 files at 1000 ms/file adds 100 s.
        assertEquals(base + 100.0, MultiSlideAcquisitionEstimator.slotSec(100, 0, 1000), 1e-9);
        // Negative guards: never below the per-slide startup.
        assertEquals(base, MultiSlideAcquisitionEstimator.slotSec(-5, -5, 1000), 1e-9);
    }

    @Test
    void batchSummary_emptyIsGraceful() {
        var empty = new MultiSlideAcquisitionEstimator.BatchEstimate(List.of(), 0, 0, 0, 0, false);
        assertTrue(empty.summary().toLowerCase().contains("no set-up slides"));
    }
}
