package qupath.ext.qpsc.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import org.junit.jupiter.api.Test;

/** The one flip helper every image, annotation and macro-frame flip in QPSC goes through. */
class FlipTransformTest {

    private static Point2D apply(AffineTransform t, double x, double y) {
        return t.transform(new Point2D.Double(x, y), null);
    }

    @Test
    void eachFlipMirrorsAboutTheImageCenter() {
        double w = 400, h = 300;
        for (boolean fx : new boolean[] {false, true}) {
            for (boolean fy : new boolean[] {false, true}) {
                AffineTransform t = TransformationFunctions.createFlipTransform(fx, fy, w, h);
                Point2D p = apply(t, 10, 20);
                assertEquals(fx ? w - 10 : 10, p.getX(), 1e-9, "x for flipX=" + fx + " flipY=" + fy);
                assertEquals(fy ? h - 20 : 20, p.getY(), 1e-9, "y for flipX=" + fx + " flipY=" + fy);
            }
        }
    }

    @Test
    void noFlipIsTheIdentity() {
        assertTrue(TransformationFunctions.createFlipTransform(false, false, 400, 300)
                .isIdentity());
    }

    @Test
    void aFlipIsItsOwnInverse() throws Exception {
        AffineTransform t = TransformationFunctions.createFlipTransform(true, true, 400, 300);
        AffineTransform twice = new AffineTransform(t);
        twice.concatenate(t);
        assertTrue(twice.isIdentity());
    }
}
