package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Approach-from-safe-Z is refused when a focus already measured on the mounted carrier disagrees
 * with the validation record's distance by more than the headroom.
 *
 * <p>PPM, 2026-10-26: the record said focus lies 443 um from safe Z 0 (bound -693 with the 250 um
 * headroom) while the slide in the holder focused at -121. The record was keyed by scope,
 * modality and objective, not by insert or Z frame, so nothing refused it: the approach drove
 * through the focus plane and about 450 um past it before the operator cancelled.
 */
class SlotJumpApproachDistanceTest {

    private static final double HEADROOM = 250.0;

    @Test
    void theRecordOf2026_10_26IsRefusedOnceTheSlideHasFocused() {
        String problem = SlotJumpAutofocus.approachDistanceProblem(0.0, 443.0, List.of(-121.1), HEADROOM);
        assertNotNull(problem);
        assertTrue(problem.contains("-121.1") && problem.contains("443.0"), problem);
        assertTrue(problem.contains("Focus Approach Validation"), problem);
    }

    @Test
    void aCarrierWithinTheHeadroomKeepsTheLicence() {
        // The 2026-08-28 carrier: validated at -212.7, slides out to -392 -- 180 um of spread.
        List<Double> measured = Arrays.asList(-212.7, -230.0, -320.0, -324.0, -392.0);
        assertNull(SlotJumpAutofocus.approachDistanceProblem(0.0, 212.7, measured, HEADROOM));
    }

    @Test
    void theSignOfTheDistanceDoesNotMatter() {
        // Retract is the negative direction on some rigs; distances are unsigned.
        assertNull(SlotJumpAutofocus.approachDistanceProblem(-1000.0, 400.0, List.of(-600.0), HEADROOM));
        assertNotNull(SlotJumpAutofocus.approachDistanceProblem(-1000.0, 400.0, List.of(-100.0), HEADROOM));
    }

    @Test
    void nothingMeasuredMeansNothingToRefuse() {
        assertNull(SlotJumpAutofocus.approachDistanceProblem(0.0, 443.0, List.of(), HEADROOM));
        assertNull(SlotJumpAutofocus.approachDistanceProblem(0.0, 443.0, null, HEADROOM));
        assertNull(SlotJumpAutofocus.approachDistanceProblem(0.0, Double.NaN, List.of(-121.0), HEADROOM));
        assertNull(SlotJumpAutofocus.approachDistanceProblem(0.0, 443.0, Arrays.asList((Double) null), HEADROOM));
    }
}
