package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SiftAutoAlignHelper#composeSiftOffsetToStageDelta} -- the
 * sign-critical conversion from a SIFT offset to the stage displacement to ADD.
 *
 * <p>The server offset points FROM the target tile TO the current camera position
 * (it returns micro-center-minus-WSI-center, and the WSI region is centered on the
 * tile). So the stage must move by the NEGATION, mapped through the alignment
 * transform's linear part. This was verified against hardware on PPM 2026-08-08:
 * predicted stage P, SIFT-with-{@code +offset} landed at {@code P + offset}, the
 * true tile was at {@code P - offset}, and "Go to centroid" (= P) sat exactly at
 * their midpoint. These tests pin that direction plus the frame handling. No sign
 * is hard-coded in production -- direction comes from the server definition,
 * orientation from the transform -- so the tests use concrete transforms to prove
 * the composition is correct by construction.
 */
class SiftOffsetComposeTest {

    private static final double EPS = 1e-9;

    private static double[] compose(double ox, double oy, boolean fx, boolean fy, double wsiPx, AffineTransform t) {
        return SiftAutoAlignHelper.composeSiftOffsetToStageDelta(ox, oy, fx, fy, wsiPx, t);
    }

    @Test
    void nullTransform_negatesOffset() {
        // No alignment transform, no server flip: the stage delta is the pure
        // negation (move toward the target, not away from it).
        double[] d = compose(294.3, -83.7, false, false, 1.0, null);
        assertEquals(-294.3, d[0], EPS);
        assertEquals(83.7, d[1], EPS);
    }

    @Test
    void nullTransform_undoesServerFlipThenNegates() {
        // Server flip (true,true) is undone first (-294.3, 83.7), then negated for
        // the target->current direction: back to (294.3, -83.7).
        double[] d = compose(294.3, -83.7, true, true, 1.0, null);
        assertEquals(294.3, d[0], EPS);
        assertEquals(-83.7, d[1], EPS);
    }

    @Test
    void identityTransform_negatesOffset_thePpmCase() {
        // PPM: the alignment transform's linear part is identity (the flip lives in
        // the sibling pixels, not the transform), so the whole correction is the
        // negation. This is the exact fix for the observed "SIFT drove to P+offset,
        // tile was at P-offset" bug.
        AffineTransform identity = new AffineTransform();
        double[] d = compose(294.3, -83.7, false, false, 1.0, identity);
        assertEquals(-294.3, d[0], EPS);
        assertEquals(83.7, d[1], EPS);
    }

    @Test
    void xyInvertTransform_reproducesPlusOffset_theOws3Style() {
        // A rig whose alignment transform mirrors X and Y: negate (-294.3, 83.7)
        // then apply the (-1,-1) linear map -> (294.3, -83.7) == the raw +offset.
        // This is how the old raw code happened to be correct on an XY-inverted rig.
        AffineTransform xyInvert = new AffineTransform(-1, 0, 0, -1, 0, 0);
        double[] d = compose(294.3, -83.7, false, false, 1.0, xyInvert);
        assertEquals(294.3, d[0], EPS);
        assertEquals(-83.7, d[1], EPS);
    }

    @Test
    void scaleIsAppliedViaPixelConversion() {
        // wsiPixelSize converts um -> entry px; the transform's um/px scale converts
        // back. wsiPx=0.5 and scale 0.5 um/px net to 1, so result is the pure negation.
        AffineTransform scaleHalf = AffineTransform.getScaleInstance(0.5, 0.5);
        double[] d = compose(100.0, 40.0, false, false, 0.5, scaleHalf);
        assertEquals(-100.0, d[0], EPS);
        assertEquals(-40.0, d[1], EPS);
    }

    @Test
    void serverFlipThenNegateThenTransform_composeInOrder() {
        // Server flipped WSI in X (offset in the flipped frame); undo it (negate X):
        // (-50, 30); negate for direction: (50, -30); apply Y-mirror transform: (50, 30).
        AffineTransform yInvert = new AffineTransform(1, 0, 0, -1, 0, 0);
        double[] d = compose(50.0, 30.0, true, false, 1.0, yInvert);
        assertEquals(50.0, d[0], EPS);
        assertEquals(30.0, d[1], EPS);
    }

    @Test
    void translationInTransformIsIgnored_deltaOnly() {
        // deltaTransform maps displacements, not points: a large translation is ignored.
        AffineTransform withTranslation = new AffineTransform(1, 0, 0, 1, 99999, -99999);
        double[] d = compose(10.0, -20.0, false, false, 1.0, withTranslation);
        assertEquals(-10.0, d[0], EPS);
        assertEquals(20.0, d[1], EPS);
    }

    @Test
    void zeroWsiPixelSize_fallsBackToNegationOnly() {
        // Non-positive pixel size can't convert um->px, so return the direction
        // negation (no orientation map) rather than dividing by zero.
        AffineTransform xyInvert = new AffineTransform(-1, 0, 0, -1, 0, 0);
        double[] d = compose(10.0, -20.0, false, false, 0.0, xyInvert);
        assertEquals(-10.0, d[0], EPS);
        assertEquals(20.0, d[1], EPS);
    }

    @Test
    void validInputs_returnTwoElements() {
        double[] d = compose(1.0, 2.0, false, false, 1.0, new AffineTransform());
        assertEquals(2, d.length);
    }

    @Test
    void identityTransform_isNegationOfRaw() {
        // Regression: on the PPM (identity) case the delta MUST be the negation of
        // the raw offset -- driving toward the tile, not to the P+offset mirror.
        AffineTransform identity = new AffineTransform();
        double[] d = compose(294.3, -83.7, false, false, 1.0, identity);
        assertTrue(
                Math.abs(d[0] - (-294.3)) < 1e-6 && Math.abs(d[1] - 83.7) < 1e-6,
                "identity-transform stage delta must be the negation of the raw offset");
    }
}
