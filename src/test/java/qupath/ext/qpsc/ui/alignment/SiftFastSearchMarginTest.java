package qupath.ext.qpsc.ui.alignment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The margin of the fast first SIFT search, against the configured one. */
class SiftFastSearchMarginTest {

    @Test
    void isHalfTheLargerSideOfTheTile() {
        // PPM 20x tile: 357 x 267 um; configured margin 1000 um (the rig's setting in October)
        assertEquals(178.5, SiftAutoAlignHelper.fastSearchMarginUm(357, 267, 1000), 1e-9);
        assertEquals(178.5, SiftAutoAlignHelper.fastSearchMarginUm(267, 357, 1000), 1e-9);
    }

    @Test
    void isSkippedWhenNotClearlySmallerThanTheConfiguredMargin() {
        // Default 160 um margin with a 20x tile: the configured search is already about this size
        assertEquals(0, SiftAutoAlignHelper.fastSearchMarginUm(357, 267, 160));
        // Exactly at the cut-off fraction
        double cutoff = 178.5 / SiftAutoAlignHelper.FAST_SEARCH_MAX_FRACTION;
        assertEquals(0, SiftAutoAlignHelper.fastSearchMarginUm(357, 267, cutoff));
        assertEquals(178.5, SiftAutoAlignHelper.fastSearchMarginUm(357, 267, cutoff + 1), 1e-9);
    }

    @Test
    void isSkippedForATileWithNoSize() {
        assertEquals(0, SiftAutoAlignHelper.fastSearchMarginUm(0, 0, 1000));
        assertEquals(0, SiftAutoAlignHelper.fastSearchMarginUm(Double.NaN, Double.NaN, 1000));
    }
}
