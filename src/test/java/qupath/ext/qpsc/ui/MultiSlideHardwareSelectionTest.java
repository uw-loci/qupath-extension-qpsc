package qupath.ext.qpsc.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * ID extraction for the multi-slide batch hardware pickers.
 *
 * <p>The combos show {@code "Friendly name (THE_ID)"} but everything downstream -- the
 * background and white-balance calibration lookups, and the objective/detector state published
 * to the per-slide dialogs -- is keyed on the bare ID. Parsing this wrongly does not throw; it
 * silently checks calibration for an objective that does not exist and reports "no backgrounds"
 * for a rig that is fully calibrated, which would train operators to ignore the warning.
 *
 * <p>Real IDs contain parentheses-free underscores but friendly names do not always, hence the
 * last-parenthesis rule rather than the first.
 */
class MultiSlideHardwareSelectionTest {

    @Test
    void extractsTheIdFromADisplayString() {
        assertEquals(
                "LOCI_OBJECTIVE_OLYMPUS_20X_POL_001",
                MultiSlideAssignmentDialog.extractId("20x Olympus Pol (LOCI_OBJECTIVE_OLYMPUS_20X_POL_001)"));
        assertEquals(
                "LOCI_DETECTOR_JAI_001", MultiSlideAssignmentDialog.extractId("AP-3200T-USB (LOCI_DETECTOR_JAI_001)"));
    }

    @Test
    void aFriendlyNameContainingParenthesesDoesNotConfuseIt() {
        // "0.75NA (air) 20x (THE_ID)" -- the ID is in the LAST pair, not the first.
        assertEquals(
                "LOCI_OBJECTIVE_20X", MultiSlideAssignmentDialog.extractId("0.75NA (air) 20x (LOCI_OBJECTIVE_20X)"));
    }

    @Test
    void aBareIdPassesThroughUnchanged() {
        // Modality names are not wrapped in a friendly-name form.
        assertEquals("ppm_20x", MultiSlideAssignmentDialog.extractId("ppm_20x"));
    }

    @Test
    void malformedInputYieldsSomethingUsableRatherThanThrowing() {
        assertNull(MultiSlideAssignmentDialog.extractId(null));
        assertEquals("", MultiSlideAssignmentDialog.extractId(""));
        // Unbalanced: no usable ID, so return the whole string rather than a substring that
        // happens to parse -- a wrong-but-plausible ID is worse than an obviously wrong one.
        assertEquals("name (unclosed", MultiSlideAssignmentDialog.extractId("name (unclosed"));
        assertEquals("unopened)", MultiSlideAssignmentDialog.extractId("unopened)"));
    }
}
