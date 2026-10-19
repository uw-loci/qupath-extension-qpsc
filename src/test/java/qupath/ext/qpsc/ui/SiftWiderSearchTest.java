package qupath.ext.qpsc.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A SIFT miss offers a wider search instead of letting the run go on with the old alignment.
 *
 * <p>2026-10-19, JOVE_PPM: the slide's alignment was 2.5 mm out. Five SIFT runs searched 1 mm
 * around the tile, all failed with "insufficient features", and every refinement continued on
 * the wrong alignment. The 10:35 match that found it was 2.5 mm from the prediction.
 */
class SiftWiderSearchTest {

    @Test
    void theDefaultMarginWidensFarEnoughForTheMorningsError() {
        double wider = SiftAutoAlignHelper.widerSearchMarginUm(1000);
        assertThat(wider).isEqualTo(3000);
        assertThat(wider).isGreaterThan(2544);
    }

    @Test
    void thereIsNoWiderSearchPastTheCap() {
        assertThat(SiftAutoAlignHelper.widerSearchMarginUm(3000)).isEqualTo(3000);
        assertThat(SiftAutoAlignHelper.widerSearchMarginUm(4000)).isEqualTo(4000);
    }

    @Test
    void onlyANoMatchOffersIt() {
        assertThat(SiftAutoAlignHelper.isNoMatchFailure(new RuntimeException(
                        "SIFT matching failed: SIFT matching failed - insufficient features or matches")))
                .isTrue();
        assertThat(SiftAutoAlignHelper.isNoMatchFailure(
                        new IllegalStateException("Micro-Manager reports no pixel-size calibration (0 um/px)")))
                .isFalse();
        assertThat(SiftAutoAlignHelper.isNoMatchFailure(null)).isFalse();
    }
}
