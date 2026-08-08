package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SiftAutoAlignHelper#composeSiftOffsetToStageDelta} -- the
 * sign-critical conversion from a SIFT offset (a displacement in the WSI-entry
 * image frame, um) to a stage displacement.
 *
 * <p>These pin the exact behaviour that fixed the "SIFT moves to the mirror /
 * off by ~1.5 tiles" bug on PPM: the offset must be mapped through the same
 * alignment transform used to predict the tile position, NOT applied raw. No
 * sign is hard-coded in production -- it comes from the transform -- so the
 * tests use concrete transforms (identity, XY-flip, rotation) to prove the
 * composition is correct by construction.
 */
class SiftOffsetComposeTest {

    private static final double EPS = 1e-9;

    private static double[] compose(double ox, double oy, boolean fx, boolean fy, double wsiPx, AffineTransform t) {
        return SiftAutoAlignHelper.composeSiftOffsetToStageDelta(ox, oy, fx, fy, wsiPx, t);
    }

    @Test
    void nullTransform_appliesRawOffset() {
        // No alignment transform (e.g. first 3-point alignment): raw offset, no server flip.
        double[] d = compose(294.3, -83.7, false, false, 1.0, null);
        assertEquals(294.3, d[0], EPS);
        assertEquals(-83.7, d[1], EPS);
    }

    @Test
    void nullTransform_undoesServerFlip() {
        // Even without a transform, a server flip must be undone (mirror negates a displacement).
        double[] d = compose(294.3, -83.7, true, true, 1.0, null);
        assertEquals(-294.3, d[0], EPS);
        assertEquals(83.7, d[1], EPS);
    }

    @Test
    void identityTransform_equalsRaw() {
        // Axis-aligned positive scale (um==px): composed == raw. This is the OWS3-style
        // case where the old raw code happened to be correct.
        AffineTransform identity = new AffineTransform(); // um/px == 1, no flip
        double[] d = compose(294.3, -83.7, false, false, 1.0, identity);
        assertEquals(294.3, d[0], EPS);
        assertEquals(-83.7, d[1], EPS);
    }

    @Test
    void xyFlipTransform_negatesBothAxes() {
        // The PPM flipped-sibling case: the entry->stage transform mirrors X and Y
        // (scale -1,-1). Server sent flip=(false,false), so the raw offset lives in the
        // entry frame and must be negated by the transform -> lands at the true tile,
        // not the mirror. This is the exact fix for the observed ~1.5-tile error.
        AffineTransform xyFlip = new AffineTransform(-1, 0, 0, -1, 0, 0);
        double[] d = compose(294.3, -83.7, false, false, 1.0, xyFlip);
        assertEquals(-294.3, d[0], EPS);
        assertEquals(83.7, d[1], EPS);
    }

    @Test
    void scaleIsAppliedViaPixelConversion() {
        // wsiPixelSize converts um -> entry pixels; the transform's um/px scale converts
        // back. With wsiPx=0.5 and a transform of scale 0.5 um/px, net factor is 1.
        AffineTransform scaleHalf = AffineTransform.getScaleInstance(0.5, 0.5);
        double[] d = compose(100.0, 40.0, false, false, 0.5, scaleHalf);
        // 100um / 0.5 = 200 px; * 0.5 um/px = 100um. Net identity.
        assertEquals(100.0, d[0], EPS);
        assertEquals(40.0, d[1], EPS);
    }

    @Test
    void serverFlipThenTransform_composeInOrder() {
        // Unflipped-base case: server flipped WSI in X (flipX=true) so the offset is in
        // the flipped frame; undo it (negate X), THEN map through the base->stage
        // transform. Here base->stage is a pure Y-mirror.
        AffineTransform yFlip = new AffineTransform(1, 0, 0, -1, 0, 0);
        double[] d = compose(50.0, 30.0, true, false, 1.0, yFlip);
        // undo server flipX: (-50, 30); apply Y-mirror: (-50, -30).
        assertEquals(-50.0, d[0], EPS);
        assertEquals(-30.0, d[1], EPS);
    }

    @Test
    void translationInTransformIsIgnored_deltaOnly() {
        // deltaTransform must ignore the translation component (it maps displacements,
        // not points): a transform with a huge translation still yields the raw delta.
        AffineTransform withTranslation = new AffineTransform(1, 0, 0, 1, 99999, -99999);
        double[] d = compose(10.0, -20.0, false, false, 1.0, withTranslation);
        assertEquals(10.0, d[0], EPS);
        assertEquals(-20.0, d[1], EPS);
    }

    @Test
    void zeroWsiPixelSize_fallsBackToRaw() {
        // Guard: a non-positive pixel size can't convert um->px, so fall back to the
        // (server-flip-undone) raw offset rather than dividing by zero.
        AffineTransform xyFlip = new AffineTransform(-1, 0, 0, -1, 0, 0);
        double[] d = compose(10.0, -20.0, false, false, 0.0, xyFlip);
        assertEquals(10.0, d[0], EPS);
        assertEquals(-20.0, d[1], EPS);
    }

    @Test
    void nullResultNotReturned_forValidInputs() {
        // Sanity: valid inputs always yield a 2-element array, never null.
        double[] d = compose(1.0, 2.0, false, false, 1.0, new AffineTransform());
        assertEquals(2, d.length);
    }

    @Test
    void distinctFromRaw_onFlippedEntry() {
        // Regression: on a flipped entry the composed result MUST differ from the raw
        // offset (that difference is the whole bug fix).
        AffineTransform xyFlip = new AffineTransform(-1, 0, 0, -1, 0, 0);
        double[] composed = compose(294.3, -83.7, false, false, 1.0, xyFlip);
        double[] raw = {294.3, -83.7};
        boolean differs = Math.abs(composed[0] - raw[0]) > 1.0 || Math.abs(composed[1] - raw[1]) > 1.0;
        assertTrue(differs, "composed stage delta must differ from the raw offset on a flipped entry");
    }
}
