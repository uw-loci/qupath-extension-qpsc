package qupath.ext.qpsc.ui.stagemap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the per-slot calibration path in {@link StageInsert#fromConfigMap}: captured
 * per-slot centers (slideK_center_x_um/_y_um) build one slide per slot centered on the
 * capture, and take precedence over the num_slides / slide_spacing_mm fixed pitch.
 */
class StageInsertPerSlotTest {

    /** A quad_v-like holder with a non-inverted aperture (origin at 0,0) and a fixed pitch. */
    private static Map<String, Object> quadBase() {
        Map<String, Object> c = new HashMap<>();
        c.put("name", "Quad");
        c.put("kind", "slide_holder");
        c.put("aperture_left_x_um", 0.0);
        c.put("aperture_right_x_um", 120000.0);
        c.put("aperture_top_y_um", 0.0);
        c.put("aperture_bottom_y_um", 75000.0);
        c.put("slide_width_mm", 25.0);
        c.put("slide_height_mm", 75.0);
        c.put("num_slides", 4);
        c.put("slide_spacing_mm", 30.0);
        return c;
    }

    private static double centerX(StageInsert insert, StageInsert.SlidePosition s) {
        return (s.getMinStageX(insert.getOriginXUm()) + s.getMaxStageX(insert.getOriginXUm())) / 2.0;
    }

    private static double centerY(StageInsert insert, StageInsert.SlidePosition s) {
        return (s.getMinStageY(insert.getOriginYUm()) + s.getMaxStageY(insert.getOriginYUm())) / 2.0;
    }

    @Test
    @DisplayName("per-slot centers build one slide per slot, centered on the capture, over pitch")
    void perSlotCentersHonoredAndOverridePitch() {
        Map<String, Object> c = quadBase();
        // Centers chosen to differ from the fixed-pitch layout (which centers slot 1 at x=15000).
        double[][] centers = {{20000, 37500}, {50000, 37500}, {80000, 37500}, {100000, 37500}};
        for (int k = 1; k <= 4; k++) {
            c.put("slide" + k + "_center_x_um", centers[k - 1][0]);
            c.put("slide" + k + "_center_y_um", centers[k - 1][1]);
        }
        StageInsert insert = StageInsert.fromConfigMap("quad_v", c, 2000);
        List<StageInsert.SlidePosition> slides = insert.getSlides();
        assertEquals(4, slides.size());
        for (int k = 0; k < 4; k++) {
            assertEquals(centers[k][0], centerX(insert, slides.get(k)), 1.0, "slot " + (k + 1) + " center X");
            assertEquals(centers[k][1], centerY(insert, slides.get(k)), 1.0, "slot " + (k + 1) + " center Y");
        }
    }

    @Test
    @DisplayName("partial per-slot capture builds only the calibrated slots")
    void partialPerSlotCapture() {
        Map<String, Object> c = quadBase();
        c.put("slide1_center_x_um", 20000.0);
        c.put("slide1_center_y_um", 37500.0);
        c.put("slide2_center_x_um", 55000.0);
        c.put("slide2_center_y_um", 37500.0);
        StageInsert insert = StageInsert.fromConfigMap("quad_v", c, 2000);
        List<StageInsert.SlidePosition> slides = insert.getSlides();
        assertEquals(2, slides.size(), "only slots with both center coords are built");
        assertEquals(20000.0, centerX(insert, slides.get(0)), 1.0);
        assertEquals(55000.0, centerX(insert, slides.get(1)), 1.0);
    }

    @Test
    @DisplayName("without per-slot centers, num_slides/pitch still yields 4 slides")
    void pitchFallback() {
        StageInsert insert = StageInsert.fromConfigMap("quad_v", quadBase(), 2000);
        assertEquals(4, insert.getSlides().size());
    }
}
