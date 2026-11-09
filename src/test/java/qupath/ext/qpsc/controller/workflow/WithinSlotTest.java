package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import org.junit.jupiter.api.Test;

/**
 * A reused per-slide alignment must put the slide in the slot it is mounted in now. Numbers from
 * the PPM quad_v batch of 2026-11-09: slot centers X 35661 (1), 7520 (2), -20416 (3), all at
 * Y -3883; the batch reused alignments from May and April that placed slides 142 and 163 in the
 * empty slot 2.
 */
class WithinSlotTest {

    private static final double[] SLOT1 = {35661.0, -3883.0};
    private static final double[] SLOT2 = {7520.5, -3883.0};
    private static final double[] SLOT3 = {-20416.5, -3883.0};

    private static double[] centre(AffineTransform t, double w, double h) {
        Point2D p = t.transform(new Point2D.Double(w / 2, h / 2), null);
        return new double[] {p.getX(), p.getY()};
    }

    @Test
    void mayAlignmentOfSlide142IsNotInSlot1() {
        AffineTransform may = new AffineTransform(0.250552, 0, 0, 0.250552, -1964.3374533749993, -21369.05302440625);
        assertFalse(WorkflowHelpers.withinSlot(centre(may, 81920, 114688), SLOT1, SLOT2));
    }

    @Test
    void freshAlignmentOfSlide142IsInSlot1() {
        AffineTransform fresh = new AffineTransform(
                0.250924491282488,
                7.30170047402E-4,
                -7.30170047402E-4,
                0.250924491282488,
                23633.898633036515,
                -33856.37091389643);
        double[] c = centre(fresh, 81920, 114688);
        assertEquals(33869.9, c[0], 1.0, "the center the run recorded");
        assertTrue(WorkflowHelpers.withinSlot(c, SLOT1, SLOT2));
    }

    @Test
    void aprilAlignmentOfSlide163IsNotInSlot3() {
        AffineTransform april = new AffineTransform(0.250552, 0, 0, 0.250552, 2184.3979039999977, -17295.540935999998);
        assertFalse(WorkflowHelpers.withinSlot(centre(april, 73728, 106496), SLOT3, SLOT2));
    }

    @Test
    void distanceAlongTheSlotDoesNotCount() {
        // 30 mm up a vertical slot is still that slot
        assertTrue(WorkflowHelpers.withinSlot(new double[] {35661, -33883}, SLOT1, SLOT2));
    }

    @Test
    void halfwayToTheNextSlotOnEitherSideIsOutside() {
        double half = (SLOT1[0] - SLOT2[0]) / 2;
        assertTrue(WorkflowHelpers.withinSlot(new double[] {SLOT1[0] - half + 10, -3883}, SLOT1, SLOT2));
        assertFalse(WorkflowHelpers.withinSlot(new double[] {SLOT1[0] - half - 10, -3883}, SLOT1, SLOT2));
        assertFalse(WorkflowHelpers.withinSlot(new double[] {SLOT1[0] + half + 10, -3883}, SLOT1, SLOT2));
    }
}
