package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.ui.stagemap.StageInsert;

/**
 * Regression test for {@link MultiSlideAssignmentDialog#defaultRotationForInsert}: a
 * HORIZONTAL insert (single_h) must default the macro rotation to 0, so switching to it
 * does not re-apply a sticky quarter-turn left over from an earlier vertical (quad_v)
 * setup -- the squished/rotated single-slide preview bug.
 *
 * <p>Only the horizontal + degenerate cases are asserted here: they short-circuit before
 * reading the persisted quarter-turn preference, so the test needs no JavaFX prefs backing.
 */
class MultiSlideAssignmentDialogRotationTest {

    private static Map<String, Object> singleHorizontal() {
        // Mirrors config_PPM.yml single_h: 75mm wide x 25mm tall -> horizontal slot.
        Map<String, Object> c = new HashMap<>();
        c.put("name", "Single Slide (Horizontal)");
        c.put("aperture_left_x_um", 0.0);
        c.put("aperture_right_x_um", 75000.0);
        c.put("aperture_top_y_um", 0.0);
        c.put("aperture_bottom_y_um", 25000.0);
        c.put("slide_width_mm", 75.0);
        c.put("slide_height_mm", 25.0);
        return c;
    }

    @Test
    @DisplayName("horizontal insert (single_h) defaults macro rotation to 0")
    void horizontalInsertDefaultsToZero() {
        StageInsert insert = StageInsert.fromConfigMap("single_h", singleHorizontal(), 2000);
        assertEquals(0, MultiSlideAssignmentDialog.defaultRotationForInsert(insert));
    }

    @Test
    @DisplayName("null / empty insert defaults to 0 (no rotation)")
    void nullInsertDefaultsToZero() {
        assertEquals(0, MultiSlideAssignmentDialog.defaultRotationForInsert(null));
    }
}
