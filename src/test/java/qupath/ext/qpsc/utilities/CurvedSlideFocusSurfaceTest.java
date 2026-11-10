package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The slide focus surface read from the server's focus_surface.json, with the same twisted
 * slide as the server's tests (PPM 20x, 2026-11-07).
 */
class CurvedSlideFocusSurfaceTest {

    private static Map<String, Object> serverRecord(boolean licensed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", "quadratic");
        r.put("licensed", licensed);
        r.put("centre_x_um", 6835.0);
        r.put("centre_y_um", -5443.0);
        r.put("z0_um", -51.9);
        r.put("tilt_x_um_per_mm", 5.67);
        r.put("tilt_y_um_per_mm", -2.65);
        r.put("qxx_um_per_mm2", -0.011);
        r.put("qxy_um_per_mm2", -0.094);
        r.put("qyy_um_per_mm2", -0.012);
        r.put("bounds_um", List.of(-2165.0, 15835.0, -14443.0, 3557.0));
        r.put("inlier_rms_um", 0.7);
        r.put("inliers", 1205.0);
        return r;
    }

    private static double twisted(double x, double y) {
        double u = (x - 6835) / 1000.0;
        double v = (y + 5443) / 1000.0;
        return -51.9 + 5.67 * u - 2.65 * v - 0.011 * u * u - 0.094 * u * v - 0.012 * v * v;
    }

    @Test
    void insideItsPointsItFollowsTheCurve() {
        var s = AffineTransformManager.fromServerFocusSurface(serverRecord(true), "20x");
        assertNotNull(s);
        assertTrue(s.curved());
        assertEquals(1205, s.pointCount());
        // A corner, where the plane alone was about 5 um off.
        assertEquals(twisted(15000, -14000), s.predict(15000, -14000), 1e-9);
    }

    @Test
    void outsideItsPointsItContinuesStraight() {
        var s = AffineTransformManager.fromServerFocusSurface(serverRecord(true), "20x");
        double edge = s.predict(15835, -5443);
        double slope = (s.predict(15835, -5443) - s.predict(15735, -5443)) / 0.1;
        assertEquals(edge + slope * 10, s.predict(25835, -5443), 0.05);
    }

    @Test
    void anUnlicensedSurfaceIsNotKept() {
        assertNull(AffineTransformManager.fromServerFocusSurface(serverRecord(false), "20x"));
        assertNull(AffineTransformManager.fromServerFocusSurface(null, "20x"));
    }

    @Test
    void aPlaneRecordStaysAPlane() {
        Map<String, Object> r = serverRecord(true);
        r.put("model", "plane");
        var s = AffineTransformManager.fromServerFocusSurface(r, "20x");
        assertFalse(s.curved());
        assertEquals(-51.9 + 5.67 * 1, s.predict(7835, -5443), 1e-9);
    }
}
