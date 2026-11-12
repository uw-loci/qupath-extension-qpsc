package qupath.ext.qpsc.ui.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.ui.acquisition.ExistingImageAcquisitionController.RefinementChoice;

/** The "Next:" line above the Acquire from Existing Image buttons follows the workflow's routing. */
class NextStepsTextTest {

    @Test
    void presetThenSingleTileThenAcquire() {
        assertEquals(
                "Next: place the slide with the preset 'Ocus40_to_PPM', refine on one tile, then acquire 3 regions.",
                ExistingImageAcquisitionController.nextStepsText(
                        false, false, false, "Ocus40_to_PPM", RefinementChoice.SINGLE_TILE, 3, false));
    }

    @Test
    void multiSlideSetUpPassSaysNothingIsAcquired() {
        assertEquals(
                "Next: place the slide with the preset 'P', refine on two or more tiles, then save it for "
                        + "Step 2: Acquire All Set-Up (nothing is acquired now).",
                ExistingImageAcquisitionController.nextStepsText(
                        false, false, false, "P", RefinementChoice.MULTI_TILE, 2, true));
    }

    @Test
    void aSavedSlideAlignmentIsUsedBeforeTheChosenOne() {
        // The workflow routes to the saved slide alignment even when manual alignment is chosen.
        assertEquals(
                "Next: use this slide's saved alignment, then acquire 1 region.",
                ExistingImageAcquisitionController.nextStepsText(
                        false, true, true, null, RefinementChoice.NONE, 1, false));
    }

    @Test
    void manualAlignmentHasNoRefinementStep() {
        assertEquals(
                "Next: align the slide by hand, then acquire 2 regions.",
                ExistingImageAcquisitionController.nextStepsText(
                        false, true, false, "P", RefinementChoice.NONE, 2, false));
    }

    @Test
    void aSubAcquisitionUsesItsOwnStagePosition() {
        assertEquals(
                "Next: place the regions from this image's recorded stage position, then acquire 4 regions.",
                ExistingImageAcquisitionController.nextStepsText(
                        true, false, true, "P", RefinementChoice.NONE, 4, false));
    }

    @Test
    void noRegionsYetStillSaysAcquire() {
        assertEquals(
                "Next: place the slide with a scanner preset, realign it by hand, then acquire.",
                ExistingImageAcquisitionController.nextStepsText(
                        false, false, false, null, RefinementChoice.FULL_MANUAL, 0, false));
    }
}
