package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import org.junit.jupiter.api.Test;

/** A region is checked against the stage limits, not the image it was drawn on. */
class StageLimitViolationTest {

    /** The LC-PolScope transform of 2026-11-01: a scan 24.6 mm tall with its bottom edge 549 um past the limit. */
    private static final AffineTransform SCAN = new AffineTransform(0.250552, 0, 0, 0.250552, -14972.15, -8080.64);

    private static final double[] LIMITS = {-21000, 51000, -9100, 16000};

    @Test
    void regionFitsThoughTheImageDoesNot() {
        assertNotNull(
                TransformationFunctions.stageLimitViolation(SCAN, new double[] {0, 0, 122880, 98304}, 0, 0, LIMITS));
        assertNull(TransformationFunctions.stageLimitViolation(
                SCAN, new double[] {8506, 7506, 108084, 85797}, 277, 277, LIMITS));
    }

    @Test
    void regionPastTheLimitIsNamedWithTheLimitItCrosses() {
        String problem =
                TransformationFunctions.stageLimitViolation(SCAN, new double[] {1000, 90000, 1000, 8304}, 0, 0, LIMITS);
        assertEquals("reaches stage Y 16550 um; the stage limit is 16000 um", problem);
    }

    @Test
    void marginCountsAgainstTheLimit() {
        double[] bounds = {1000, 90000, 1000, 5000};
        assertNull(TransformationFunctions.stageLimitViolation(SCAN, bounds, 0, 0, LIMITS));
        assertTrue(TransformationFunctions.stageLimitViolation(SCAN, bounds, 0, 400, LIMITS)
                .contains("stage Y"));
    }

    @Test
    void mirroredStageAndSwappedLimitsAreBothHandled() {
        AffineTransform mirrored = new AffineTransform(-1, 0, 0, -1, 0, 0);
        double[] swapped = {100, -100, 100, -100};
        assertNull(TransformationFunctions.stageLimitViolation(mirrored, new double[] {10, 10, 50, 50}, 0, 0, swapped));
        assertEquals(
                "reaches stage X -200 um; the stage limit is -100 um",
                TransformationFunctions.stageLimitViolation(mirrored, new double[] {150, 10, 50, 50}, 0, 0, swapped));
    }
}
