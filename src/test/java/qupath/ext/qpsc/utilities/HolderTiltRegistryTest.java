package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tilt inheritance rests on two measured slides, so the registry's job is to keep
 * testing that premise instead of assuming it.
 *
 * <p>The evidence: within the 2026-08-12 loading, two slides tilted (-7.67, -4.78) and
 * (-7.65, -5.59) um/mm while their heights differed by 95 um. Across sessions, tilt
 * ranged from -10.4 to +2.9 um/mm. So tilt may be the holder's and height is certainly
 * the slide's -- on two slides' worth of data, which is why nothing here inherits until
 * the slides in front of it actually agree.
 */
class HolderTiltRegistryTest {

    @BeforeEach
    void reset() {
        HolderTiltRegistry.clear();
    }

    private static AffineTransformManager.SlideFocusSurface surface(double tiltX, double tiltY, int points) {
        return new AffineTransformManager.SlideFocusSurface(
                -300.0, tiltX, tiltY, 0, 0, points, points > 3 ? 0.6 : Double.NaN, "20x", "now");
    }

    @Test
    void oneSlideIsNotEvidenceAboutAHolder() {
        HolderTiltRegistry.record("slideA", surface(-7.67, -4.78, 6));
        assertThat(HolderTiltRegistry.agreedTilt(null)).isEmpty();
    }

    @Test
    void twoAgreeingSlidesGiveAHolderTilt() {
        // The real measured pair.
        HolderTiltRegistry.record("slideA", surface(-7.67, -4.78, 6));
        HolderTiltRegistry.record("slideB", surface(-7.65, -5.59, 6));
        var tilt = HolderTiltRegistry.agreedTilt(null);
        assertThat(tilt).isPresent();
        assertThat(tilt.get().slideCount()).isEqualTo(2);
        assertThat(tilt.get().umPerMmX()).isCloseTo(-7.66, org.assertj.core.data.Offset.offset(0.05));
        assertThat(tilt.get().spreadUmPerMm()).isLessThan(2.0);
    }

    @Test
    void slidesThatDisagreeInheritNothing() {
        // The 2026-09-17 session, where tilt was clearly not a holder property:
        // (+2.33, -1.00) on one slide and (-10.39, -3.07) on another.
        HolderTiltRegistry.record("slideA", surface(2.33, -1.00, 6));
        HolderTiltRegistry.record("slideB", surface(-10.39, -3.07, 6));
        assertThat(HolderTiltRegistry.agreedTilt(null))
                .as("averaging these would describe neither slide")
                .isEmpty();
    }

    @Test
    void anUncheckedThreePointSurfaceIsNotEvidence() {
        // Its tilt could be anything: three points fit a plane exactly, so the fit
        // cannot disagree with them.
        HolderTiltRegistry.record("slideA", surface(-7.67, -4.78, 3));
        HolderTiltRegistry.record("slideB", surface(-7.65, -5.59, 3));
        assertThat(HolderTiltRegistry.slideCount()).isZero();
        assertThat(HolderTiltRegistry.agreedTilt(null)).isEmpty();
    }

    @Test
    void aSlideCannotInheritFromItself() {
        HolderTiltRegistry.record("slideA", surface(-7.67, -4.78, 6));
        HolderTiltRegistry.record("slideB", surface(-7.65, -5.59, 6));
        // Excluding slideB leaves only slideA, which is one slide and so not evidence.
        assertThat(HolderTiltRegistry.agreedTilt("slideB")).isEmpty();
    }

    @Test
    void reRecordingASlideReplacesItRatherThanVotingTwice() {
        HolderTiltRegistry.record("slideA", surface(-7.67, -4.78, 6));
        HolderTiltRegistry.record("slideA", surface(2.00, 1.00, 6));
        HolderTiltRegistry.record("slideB", surface(1.90, 1.10, 6));
        var tilt = HolderTiltRegistry.agreedTilt(null);
        assertThat(tilt).isPresent();
        assertThat(tilt.get().slideCount()).isEqualTo(2);
        assertThat(tilt.get().umPerMmX()).isCloseTo(1.95, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void clearingForgetsTheLoading() {
        HolderTiltRegistry.record("slideA", surface(-7.67, -4.78, 6));
        HolderTiltRegistry.record("slideB", surface(-7.65, -5.59, 6));
        HolderTiltRegistry.clear();
        assertThat(HolderTiltRegistry.slideCount()).isZero();
        assertThat(HolderTiltRegistry.agreedTilt(null)).isEmpty();
    }
}
