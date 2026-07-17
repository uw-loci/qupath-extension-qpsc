package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure (I/O-free) pieces of the multi-slide run-time estimate: the shared
 * tiles-per-annotation grid math and the batch summary formatting.
 */
class MultiSlideEstimateTest {

    @Test
    void tileCount_exactFit_noOverlap() {
        // 1000x1000 um region, 100x100 um frame, 0% overlap -> 10x10 = 100 tiles.
        assertEquals(100, TilingUtilities.estimateTileCount(1000, 1000, 100, 100, 0));
    }

    @Test
    void tileCount_ceilsPartialTiles() {
        // 950 um / 100 um step -> ceil(9.5) = 10 columns; 100 um / 100 -> 1 row.
        assertEquals(10, TilingUtilities.estimateTileCount(950, 100, 100, 100, 0));
    }

    @Test
    void tileCount_overlapShrinksEffectiveStep() {
        // 10% overlap -> effective step 90 um; ceil(1000/90) = 12 per axis -> 144.
        assertEquals(144, TilingUtilities.estimateTileCount(1000, 1000, 100, 100, 10));
    }

    @Test
    void tileCount_degenerateInputsReturnAtLeastOne() {
        assertEquals(1, TilingUtilities.estimateTileCount(0, 0, 100, 100, 0));
        assertEquals(1, TilingUtilities.estimateTileCount(100, 100, 0, 0, 0));
        // 100% overlap -> zero effective step -> guarded to 1, not a divide-by-zero.
        assertEquals(1, TilingUtilities.estimateTileCount(1000, 1000, 100, 100, 100));
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
    void batchSummary_emptyIsGraceful() {
        var empty = new MultiSlideAcquisitionEstimator.BatchEstimate(List.of(), 0, 0, 0, 0, false);
        assertTrue(empty.summary().toLowerCase().contains("no set-up slides"));
    }
}
