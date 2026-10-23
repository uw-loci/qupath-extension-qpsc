package qupath.ext.qpsc.controller.workflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Approach-from-safe-Z is refused when the stage is on the far side of safe Z from the validated
 * approach. PPM, 2026-10-22: the record said safe Z 0 with the sample toward -693, while the slide
 * was in focus at +258. The approach "retracted" 258 um toward the sample's side and scanned on
 * down, nowhere near focus.
 */
class SlotJumpApproachSideTest {

    @Test
    void theRigOn2026_10_22IsRefused() {
        String problem = SlotJumpAutofocus.approachSideProblem(0.0, -693.0, 257.8);
        assertNotNull(problem);
        assertTrue(problem.contains("257.8") && problem.contains("Focus Approach Validation"), problem);
    }

    @Test
    void aStageBetweenSafeZAndTheSampleIsLicensed() {
        assertNull(SlotJumpAutofocus.approachSideProblem(0.0, -693.0, -420.0));
        assertNull(
                SlotJumpAutofocus.approachSideProblem(0.0, -693.0, -900.0), "past the bound is still the sample side");
        // Retract is the negative direction on some rigs: the sign of the bound decides, not a convention.
        assertNull(SlotJumpAutofocus.approachSideProblem(-1000.0, 650.0, -500.0));
        assertNotNull(SlotJumpAutofocus.approachSideProblem(-1000.0, 650.0, -1200.0));
    }

    @Test
    void atSafeZOrWithoutDataThereIsNothingToRefuse() {
        assertNull(SlotJumpAutofocus.approachSideProblem(0.0, -693.0, 0.4));
        assertNull(SlotJumpAutofocus.approachSideProblem(0.0, Double.NaN, 257.8));
    }
}
