package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SiftAutoAlignHelper#composeSiftOffsetToStageDelta} -- the
 * sign-critical conversion from a SIFT offset to the stage displacement to ADD.
 *
 * <p>The move is simply {@code -offset} (after undoing any server flip), with <b>no
 * rig transform</b>. The server offset points FROM the target tile TO the current
 * camera position, and SIFT matches the WSI region against the live camera, so the
 * match itself resolves the light path -- the offset is already in the stage-command
 * frame for a relative correction. Verified on both PPM (composite identity) and OWS3
 * (composite INVERT_XY) 2026-08-09: {@code -offset} lands correctly on both, and it is
 * the only operation consistent with both. An earlier version mapped the offset through
 * the per-slide alignment transform; that mirrored the move on OWS3 (its flip lives in
 * the transform there, not the sibling pixels).
 */
class SiftOffsetComposeTest {

    private static final double EPS = 1e-9;

    private static double[] compose(double ox, double oy, boolean fx, boolean fy) {
        return SiftAutoAlignHelper.composeSiftOffsetToStageDelta(ox, oy, fx, fy);
    }

    @Test
    void noServerFlip_negatesOffset() {
        // The move is toward the target = the negation of the (target->current) offset.
        double[] d = compose(294.3, -83.7, false, false);
        assertEquals(-294.3, d[0], EPS);
        assertEquals(83.7, d[1], EPS);
    }

    @Test
    void serverFlipBothAxes_undoneThenNegated() {
        // Server flip (true,true) is undone first (-294.3, 83.7), then negated for the
        // target->current direction: back to (294.3, -83.7).
        double[] d = compose(294.3, -83.7, true, true);
        assertEquals(294.3, d[0], EPS);
        assertEquals(-83.7, d[1], EPS);
    }

    @Test
    void serverFlipXonly_negatesXundoThenNegate() {
        // Undo server flipX: (-50, 30); negate: (50, -30).
        double[] d = compose(50.0, 30.0, true, false);
        assertEquals(50.0, d[0], EPS);
        assertEquals(-30.0, d[1], EPS);
    }

    @Test
    void ppmAndOws3_bothGetMinusOffset() {
        // The whole point: independent of the rig (no composite/transform involved),
        // the stage delta is -offset. Same call, same result, whatever the scope.
        double[] d = compose(-80.9, 174.6, false, false);
        assertEquals(80.9, d[0], EPS);
        assertEquals(-174.6, d[1], EPS);
    }

    @Test
    void validInputs_returnTwoElements() {
        double[] d = compose(1.0, 2.0, false, false);
        assertEquals(2, d.length);
    }
}
