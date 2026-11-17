package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** With autofocus off, slides whose set-up focus points disagree are named before the run. */
class AutofocusOffFocusSpreadTest {

    @Test
    void theSpreadIsTheFocusRange() {
        assertEquals(0.0, MultiSlidePreflight.focusSpreadUm(List.of(new double[] {0, 0, 72.6})));
        assertEquals(
                51.8,
                MultiSlidePreflight.focusSpreadUm(
                        List.of(new double[] {0, 0, 124.4}, new double[] {1, 1, 72.6}, new double[] {2, 2, 100})),
                1e-9);
    }

    @Test
    void onlySlidesBeyondTheLimitAreListed() {
        Map<String, List<double[]>> points = new LinkedHashMap<>();
        points.put("slot 1 (MetroHealth_370)", List.of(new double[] {0, 0, -26.0}, new double[] {5, 5, -24.5}));
        points.put("slot 4 (MetroHealth_189)", List.of(new double[] {0, 0, 124.4}, new double[] {5, 5, 72.6}));
        points.put("slot 2 (no points)", List.of());
        assertEquals(
                List.of("slot 4 (MetroHealth_189): 51.8 um apart"),
                MultiSlidePreflight.slidesWithFocusSpread(points, 15.0));
    }
}
