package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.AffineTransform;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.AffineTransformManager.TransformPreset;

/**
 * Which saved transform a dialog preselects, and how old it is.
 *
 * <p>The preset list is sorted by name, so the old "select the first one" preselected
 * whichever name sorted first -- on PPM a March preset, months old, whose age-decayed
 * confidence rendered as red "LOW" right after the operator made a fresh alignment.
 */
class PresetSelectionTest {

    private static Date daysAgo(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -days);
        return c.getTime();
    }

    /**
     * TransformPreset stamps createdDate at construction, so an aged preset can only be
     * built by writing the field. Reflection keeps the production class free of a
     * test-only constructor.
     */
    private static TransformPreset preset(String name, Date created) {
        TransformPreset p = new TransformPreset(name, "PPM", "Ocus40", new AffineTransform(), "", null, 1.0, 0.0);
        if (created != null) {
            try {
                java.lang.reflect.Field f = TransformPreset.class.getDeclaredField("createdDate");
                f.setAccessible(true);
                f.set(p, created);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("createdDate field moved; update this test", e);
            }
        } else {
            try {
                java.lang.reflect.Field f = TransformPreset.class.getDeclaredField("createdDate");
                f.setAccessible(true);
                f.set(p, null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("createdDate field moved; update this test", e);
            }
        }
        return p;
    }

    @Test
    void theNewestPresetIsChosenNotTheFirstByName() {
        // Name order would pick "Ocus40_PPM"; date order picks the May one.
        TransformPreset march = preset("Ocus40_PPM", daysAgo(174));
        TransformPreset may = preset("PPM_Transform_20260531", daysAgo(109));
        assertEquals(may, AlignmentHelper.newestPreset(List.of(march, may)));
        assertEquals(may, AlignmentHelper.newestPreset(List.of(may, march)));
    }

    @Test
    void anEmptyListSelectsNothing() {
        assertNull(AlignmentHelper.newestPreset(List.of()));
        assertNull(AlignmentHelper.newestPreset(null));
    }

    @Test
    void presetsWithoutDatesFallBackToTheFirstEntry() {
        TransformPreset a = preset("A", null);
        TransformPreset b = preset("B", null);
        assertEquals(a, AlignmentHelper.newestPreset(List.of(a, b)));
    }

    @Test
    void aDatedPresetBeatsAnUndatedOne() {
        TransformPreset undated = preset("A_undated", null);
        TransformPreset dated = preset("Z_dated", daysAgo(200));
        assertEquals(dated, AlignmentHelper.newestPreset(List.of(undated, dated)));
    }

    @Test
    void ageIsReportedInWholeDays() {
        assertEquals(30, AlignmentHelper.ageInDays(preset("x", daysAgo(30))));
        assertEquals(-1, AlignmentHelper.ageInDays(preset("x", null)));
        assertEquals(-1, AlignmentHelper.ageInDays(null));
    }

    @Test
    void confidenceIsAtItsFloorForAnythingPastTheAgeCap() {
        // 0.65 base - 0.003/day capped at 0.3. So ~100 days is the floor, and a preset any
        // older cannot read better than LOW no matter how well it works -- the reason the
        // label must say it is measuring age.
        double hundredDays = AlignmentHelper.calculateConfidence(preset("x", daysAgo(100)));
        double twoYears = AlignmentHelper.calculateConfidence(preset("x", daysAgo(730)));
        assertEquals(hundredDays, twoYears, 1e-9);
        assertTrue(hundredDays < 0.5, "expected the floor to read as LOW, got " + hundredDays);
    }

    @Test
    void aFreshPresetReadsBetterThanTheFloor() {
        assertTrue(AlignmentHelper.calculateConfidence(preset("x", daysAgo(0)))
                > AlignmentHelper.calculateConfidence(preset("x", daysAgo(200))));
    }
}
