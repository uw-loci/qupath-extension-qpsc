package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.geom.AffineTransform;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Stage Map's macro overlay follows a slide when refinement moves its alignment.
 *
 * <p>Numbers from the 2026-10-19 JOVE_PPM run: single-tile refinement moved the slide's
 * transform translation from (-1142.5, -4883.8) to (-959.9, -7427.9) um, a 2.5 mm shift the
 * overlay never showed.
 */
class MacroOverlayShiftTest {

    private static final int W = 81946;
    private static final int H = 30726;

    private static AffineTransform slide(double tx, double ty) {
        return new AffineTransform(0.250552, 0, 0, 0.250552, tx, ty);
    }

    @Test
    void aTranslationRefinementShiftsTheOverlayByTheSameAmount() {
        double[] shift = AffineTransformManager.accumulateOverlayShift(
                null, slide(-1142.518, -4883.847), slide(-959.906, -7427.946), W, H);
        assertThat(shift[0]).isCloseTo(182.61, within(0.01));
        assertThat(shift[1]).isCloseTo(-2544.10, within(0.01));
    }

    @Test
    void refinementsAddUp() {
        double[] first = AffineTransformManager.accumulateOverlayShift(null, slide(0, 0), slide(100, -50), W, H);
        double[] second = AffineTransformManager.accumulateOverlayShift(first, slide(100, -50), slide(130, -20), W, H);
        assertThat(second).containsExactly(new double[] {130, -20}, within(1e-9));
    }

    @Test
    void theShiftIsMeasuredAtTheImageCentreSoAFlippedFrameAgrees() {
        // The same physical placement expressed in a frame flipped in X and Y: the centre pixel
        // is the same pixel in both, so a pure frame change records no shift.
        AffineTransform base = slide(-1000, -5000);
        AffineTransform flipped = new AffineTransform(base);
        flipped.translate(W, H);
        flipped.scale(-1, -1);
        double[] shift = AffineTransformManager.accumulateOverlayShift(null, base, flipped, W, H);
        assertThat(shift).containsExactly(new double[] {0, 0}, within(1e-6));
    }

    @Test
    void withoutBothTransformsTheRecordedShiftStands() {
        double[] kept = AffineTransformManager.accumulateOverlayShift(new double[] {5, 6}, null, slide(0, 0), W, H);
        assertThat(kept).containsExactly(5, 6);
    }

    @Test
    void onlyAWellFormedPairIsRead() {
        assertThat(AffineTransformManager.parseShift(List.of(1.5, -2.0))).containsExactly(1.5, -2.0);
        assertThat(AffineTransformManager.parseShift(List.of(1.5))).isNull();
        assertThat(AffineTransformManager.parseShift("1.5,-2")).isNull();
        assertThat(AffineTransformManager.parseShift(null)).isNull();
    }
}
